# 03 — `InteractiveReplTest` 会挂死：Ctrl+D 与回合结束的竞态

Status: ready-for-agent
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

13 条里有 7 条没有 `@Timeout`，形状都是「先把按键全塞进管道，再同步跑 REPL」：

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

## 判据

- [ ] 把桩 `turnRunner` 加上 `Thread.sleep(200)`，13 条**全绿**（现在这样会挂死）——这是「竞态真的没了」的判据，光跑一遍原样绿不算
- [ ] `:337` 那条「回合运行中的 Ctrl+D 无操作」仍有测试覆盖
- [ ] `-Pquick` 仍是 1283 / 0 / 0
- [ ] 不改 `InteractiveRepl` 的产品行为

## 注意

判据不能是「跑一遍绿了」。这条 bug 在本机、在 ubuntu、在 20 分钟前的 macOS 上**都是绿的**——它对单次运行结构性失明。要么按上面那条 sleep 判据把窗口撑开，要么就别声称修好了。
