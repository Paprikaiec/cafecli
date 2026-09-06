# 03 — `InteractiveReplTest` 会挂死：Ctrl+D 与回合结束的竞态

Status: **done**（2026-09-05，`fix/interactive-repl-test-hang`）——修法见文末「落地」
Type: 测试竞态（活性）
起因: 公开发布后第二次 CI（run `33858406329`，`macos-latest`）挂满 6 小时被 GitHub 杀掉

## Parent

`.scratch/ci-and-test-quarantine/PRD.md`

## 症状

`macos-latest` 的 `mvn -B test -Pquick` 在 `09:29:03` 打出 `Running com.cafecli.cli.InteractiveReplTest`，然后**再没有输出**，直到 `15:27:55` job 撞上 GitHub 的 6 小时执行上限被取消（`Terminate orphan process: pid (1348) (java)`）。

**不是断言失败，是挂死。** 而且是 flaky：

| | 结果 |
|---|---|
| 同一次 run 的 `ubuntu-latest` | 这个类 **1.141s** 跑完，13 条全绿 |
| 20 分钟前的 run `33856706897`，同样 `macos-latest` | 全绿，4m47s |
| 本机 macOS | 一直绿 |

为什么偏偏是 macOS runner：**不知道**。只有一个样本，别当成已定位。窗口是亚毫秒级的，两个平台掷的是同一个骰子。

## 根因（已实测复现，不是推断）

`InteractiveRepl.run()` 的 Ctrl+D 分支（`src/main/java/com/cafecli/cli/InteractiveRepl.java:333`）：

```java
} catch (EndOfFileException e) {
    if (!turnIsRunning() || inputStreamHasEnded()) { sayGoodbye(); return; }
    continue;   // ← 回去接着读
}
```

13 条里有 7 条没有 `@Timeout`，形状都是「先把按键全塞进管道，再同步跑 REPL」（**修复时实测：这 7 条里
真正会挂的是 4 条**——另外 3 条是斜杠命令，压根不投回合，所以进不了竞态）：

```java
rig.type("hello\n");
rig.endOfFile();      // Ctrl+D 字节紧跟在后面
rig.repl.run();       // 必须自己返回
```

于是终止与否取决于一场竞态：**输入线程处理到 Ctrl+D**，与**桩回合跑完**，谁先。

- 回合已结束 → `turnIsRunning()` false → `sayGoodbye(); return;` → 绿。
- **回合还在跑** → Ctrl+D 被正确地当成「回合运行中的无操作」→ `continue` → 回去读一个**再也不会有字节**的管道 → 永久阻塞。

本该救场的活性守卫 `inputStreamHasEnded()`（同文件 `:278`）救不了：它要求 **5ms 内连着来两次 EOF**（`DEAD_STREAM_GAP_NANOS = 5_000_000L`）。真实的死 stdin 会以循环自己的速度连续吐 EOF，所以**生产路径上这个守卫是对的**；而 `PipedInputStream` 交出一个 Ctrl+D 之后就**阻塞**，第二次 EOF 永远不会来，守卫在这个 rig 里结构上不可能触发。

**复现**（2026-09-05，本机 macOS）：把 rig 的桩 `turnRunner` 加一句 `Thread.sleep(200)`，`aPlainMessageIsPostedToTheEventLoopAsAUserSubmit` 挂过 90 秒手动杀；不加就是 1 秒内绿。一行 sleep 把亚毫秒的竞态窗口撑成必现。

## 已经做了的（兜底，不是修法）

两条都已落地，**它们只保证挂死能被及时发现、不会再烧 6 小时，竞态本身一点没动**：

1. `.github/workflows/ci.yml` 的 job 上 `timeout-minutes: 30`。
2. `src/test/resources/junit-platform.properties`：`junit.jupiter.execution.timeout.default = 2m` + `thread.mode.default = SEPARATE_THREAD`。

第二条的 `SEPARATE_THREAD` 是实测出来的必需项，别当冗余删掉：`SAME_THREAD`（默认档）下超时设 15s，挂死的测试照样挂过 5 分钟——它只能发中断然后继续等那条线程返回，而线程卡在 `PipedInputStream.read()` 里、中断被 JLine 的读循环吞掉。换 `SEPARATE_THREAD` 之后同一条挂死在 **15.03s** 变成一条点名到方法的 `TimeoutException`。

加上之后 `-Pquick` 仍是 **1283 / 0 fail / 0 skip**，没有任何测试被新超时误伤。

## 要决定的：rig 怎么改

竞态在测试侧，不在产品侧——生产的 stdin 是真 TTY，死流会连续吐 EOF，守卫成立。所以**不要**为了让测试绿去改 `inputStreamHasEnded()` 的语义。三种形状：

| | 做法 | 代价 |
|---|---|---|
| **A** | `endOfFile()` 改成**关闭管道写端**而不是只写一个 `4` 字节。关掉的管道会**反复**交出流结束，5ms 守卫于是照设计触发，顺带真的走了一遍「死流」路径 | 改的是 7 条测试共用的终止语义；「Ctrl+D 按键」与「流结束」在语义上本来就是两件事，合并要想清楚 |
| **B** | 写 Ctrl+D 之前先等会话回到 idle（rig 已有 `waitUntil`，5s 上限） | 最小改动，但把「Ctrl+D 撞上运行中的回合」这个组合从覆盖里摘掉了——而那正是 `:337` 那条分支的意义 |
| **C** | 给那 7 条补 `@Timeout` | **不要单独选它**。那只是把挂死变成快速失败，测试仍然是 flaky 的，只是不再烧 runner。全局默认超时已经做到这一点了 |

倾向 A，但 A 会让「Ctrl+D 在回合运行中是无操作」这条断言失去一个入口，得先确认那条行为另有测试钉住（`escDuringAForegroundTurnCancelsIt` 一带看起来相关，未核）。

> **核完了（2026-09-05）**：钉住它的不是 `escDuringAForegroundTurnCancelsIt`（那条按的是 ESC），是
> `CancelKeyTableTest.ctrlDDuringATurnDoesNothing`。所以 A 的那条代价**不成立**——A 与 B 都不会让这条
> 行为失去覆盖。最终仍选 B，理由换成了别的（见文末「落地」）。

## 判据

- [x] 把桩 `turnRunner` 加上 `Thread.sleep(200)`，13 条**全绿**（修复前这样会挂死）——这是「竞态真的没了」的判据，光跑一遍原样绿不算。**实测**：修复后 armed 13 绿 3.0s，连跑 6 次全绿；修复前 armed 是 4 条各撞 2m 全局超时、单跑 8m03s
- [x] `:337` 那条「回合运行中的 Ctrl+D 无操作」仍有测试覆盖——`CancelKeyTableTest.ctrlDDuringATurnDoesNothing` 本来就在钉它（**本 issue 写下来时未核，核完是有**），且它是确定的：latch 扣住回合、REPL 跑后台线程、断言 `replReturned` 为假
- [x] `-Pquick` 仍是 1283 / 0 / 0 → 实际 **1284 / 0 / 0**（+1 是下面新增的守卫测试）
- [x] 不改 `InteractiveRepl` 的产品行为（`git diff` 里 `src/main` 一行没动）

## 注意

判据不能是「跑一遍绿了」。这条 bug 在本机、在 ubuntu、在 20 分钟前的 macOS 上**都是绿的**——它对单次运行结构性失明。要么按上面那条 sleep 判据把窗口撑开，要么就别声称修好了。

## 落地（2026-09-05）

### 走的是 B，但 B 按上表那句话写会**原样挂死**

上表给 B 的描述是「写 Ctrl+D 之前先等会话回到 idle」。照做，**实测仍然 4 条挂死**——因为那 4 条的形状是
「先把按键全塞进管道，**再**同步跑 REPL」：`endOfFile()` 被调用的那一刻 REPL 还没启动，等待谓词在
t=0 就成立，等于没等。

**根子比 issue 写的深一层**：「我敲进去的东西已经被处理完了」在这个 rig 里被**假设**成了字节顺序，而它
其实只能由处理结果的**可观察量**来判定——那要求 REPL 与测试并发跑。所以 B 必须连驱动形状一起改。
A 也逃不掉同一条（A 下 `run()` 返回不再蕴含回合已结束，4 条的断言各自要补同步点），**这一条不构成
A/B 的分野**；B 仍然更好，因为它保住「退出蕴含回合已结束」，断言一个字不用改。

三层，缺一层都会重新变成竞态或变成假绿：

1. **`runInBackground()` 全类统一**——13 条一条都不再在测试线程上同步调 `repl.run()`，各自等自己的
   可观察量（`awaitTurnsRecorded` / `awaitRouted`）。顺带的结构性收益：`run()` 不返回时**挂不死测试线程**。
2. **`endOfFile()` 等 `SessionInterruptGuard.turnIsRunning()`**——等的是 REPL 自己读的那个谓词，
   **不是 `session.isIdle()`**（后者只是它的一半，另一半是注册表里的前台令牌）。5s 内没等到就点名失败。
3. **`awaitReplReturn()` 断言 `run()` 真的返回**（替掉 6 处裸 `join(5_000)`）。

### 单层变异（这三层各自都是承重的）

| 变异 | 结果 |
|---|---|
| 拿掉第 2 层（`endOfFile()` 不等） | 同样那 4 条红，**21.7s 点名**，不再挂死 |
| 再叠加拿掉第 3 层（换回裸 `join`） | **13 绿 31.8s** —— 竞态原封不动，泄漏的 REPL 线程完全不可见 |
| 把 `\|\| inputStreamHasEnded()` 从 `:337` 删掉 | 只有新增那条守卫测试红，15.7s 点名 |

第二行就是「**错的修法读起来是绿的**」的实证：唯一的信号是墙钟从 3.0s 变 31.8s。第 3 层不是洁癖，
它是这条 bug 唯一的可见性来源。

### 顺带修的：`SessionInterruptGuardTest` 有同一条竞态

`ctrlCDuringAForegroundTurnStopsTheTurnAndTheSessionKeepsServing:297` 的 `endOfFile()` 也可能撞上运行中的
回合。后果轻得多——rig 的 `close()` 关掉写端，REPL 从死流那条路被放出来——**但代价是一次静默的 5s 停顿，
一条都不红**。实测（桩回合加 200ms）：修前 2.3s → **7.4s**，修后 **2.5s**。同样两层：等谓词 + 断言返回。

### 新增：死流守卫第一次有了测试

`inputStreamHasEnded()` 在这条 issue 之前**全仓库零覆盖**——而它正是拦着「回合运行中的 Ctrl+D 无操作」
不变成满速空转的那道闸。新增 `CancelKeyTableTest.aDeadInputStreamExitsEvenWhileATurnIsRunning`：
latch 扣住回合 → 关掉管道写端（**关写端才是死流的节奏：管道会反复交出文件尾；只写一个字节 4 是按键，
交出一次就阻塞**）→ 断言 REPL 照样退出，且**令牌没被取消**（断流不是取消键）。

判据必须是「**回合还在跑的时候**流断了」：回合跑完之后再断走的是 `!turnIsRunning()` 那条路，把守卫整个
删掉也照样绿。

**5ms 那个窗口的余量（实测本机）**：单次 EOF 往返 p50 178µs / p95 414µs / max 4688µs。常态有 25 倍余量，
但**尾巴摸得到 5ms**；守卫只需要**一对**相邻间隔落在窗口内，而空转循环一秒跑几千轮，所以这不是脆弱点。
CI 上若真出现，表现是这一条超时点名，不是挂死。

### 没做 / 边界

- **`inputStreamHasEnded()` 的语义一字未改**，`src/main` 零改动。生产路径上真实的死 stdin 会连续吐文件尾，
  守卫成立——竞态自始至终在 rig 里。
- **为什么偏偏是 macOS runner 仍然不知道**，只有一个样本。修法不依赖那个答案：竞态整个没了，不是变窄了。
- 上表的 C（只补 `@Timeout`）没有单独采用，全局 2m 默认超时已经做到那一步。
