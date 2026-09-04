# 01 — 给 6 条红测试定性并落隔离策略

Status: **done**（2026-09-03）· 走的是选项 A：6 条全修好，**隔离 0 条**
Type: 测试债务 triage

## Parent

`.scratch/ci-and-test-quarantine/PRD.md`

## 结果

`mvn test -Pquick`：**1281 tests / 6 failures → 1283 tests / 0 failures / 0 skipped**（多出的 2 条是新加的守卫，见下）。

修完之后跑过的判据，全绿：

| 跑法 | 结果 |
|---|---|
| `mvn test -Pquick`（本机 Ghostty 环境） | 1283 / 0 fail / 0 skip |
| `mvn test -Pquick`（`env -u TERM -u TERM_PROGRAM -u TERM_PROGRAM_VERSION -u COLORTERM -u TERMINFO`，模拟 CI） | 1283 / 0 fail / 0 skip |
| `mvn test -Pquick -Dsurefire.runOrder=reversealphabetical` | 1283 / 0 fail / 0 skip |
| `mvn test -Pphase16-smoke` | 455 / 0 fail / 0 skip |
| 屏幕真相 5 条按名字单跑 | 87 / 0 fail / 0 skip |
| 快照安全快检 5 个类 | 30 / 0 fail / 0 skip |

> 顺带修正 handoff 里的一个数：快照快检那条命令的期望值写的是 26，实际是 **30**
> （`HijackedProjectGitTest` 6 + `SideGitManagerTest` 2 + `SnapshotRestoreStaysInsideProjectRootTest` 6
> + `SnapshotDoesNotTouchProjectGitTest` 3 + `PathGuardTest` 13）。不是本次改动引入的——这次没碰
> 那几个类里的任何一条。

## 根因：两组是同一类缺陷的两个开关

原 PRD 说「两组性质完全不同」。**测量下来是同一类**：判据读到了测试自己控制不了的东西——一个是**宿主环境**，一个是**JVM 全局 + 类加载顺序**。两组都不是渲染缺陷，渲染代码一行没改。

### A 组：5 × `InlineRendererTest` —— 读到了跑测试的那个终端

`TerminalCapabilities.supportsSynchronizedOutput()` 在 `terminal.getType()` 不在硬编码名单里时，会回退去读进程的 `TERM_PROGRAM` 环境变量。测试用 `Mockito.mock(Terminal.class)` 造终端正是为了控制能力，而这条回退**绕过了 mock**，读的是跑 Maven 的那个终端。

这台机器 `TERM_PROGRAM=ghostty` 在名单里 → 每帧被 `ESC[?2026h … ESC[?2026l` 包住 → 逐字节断言全对不上。

**实测（单变量，同一份代码）**：

```
env -u TERM_PROGRAM mvn test -Dtest=InlineRendererTest -DskipTests=false   # 60 tests / 0 failures
TERM_PROGRAM=ghostty mvn test -Dtest=InlineRendererTest -DskipTests=false  # 60 tests / 5 failures
```

那 5 条的报错也自洽：`readingPushIsIncludedInRenderWriteByteAndFlushAccounting` 期望 17 字节、实得 33——差的 16 正好是 `ESC[?2026h`(8) + `ESC[?2026l`(8)。其余 4 条的失败消息里能看到 `2026h` / `2026l`（`ESC[?` 被 surefire 的日志过滤吃掉了，所以肉眼看像是凭空多出来的数字）。

**修法**：pom 里加 `${test.argLine}`，测试期钉死 `-Dpaicli.render.synchronized-output=false -Dpaicli.render.osc8=false`，三份 surefire 配置（default / quick / phase16-smoke）全走它。钉死之后 mock 才是测试里唯一的能力来源，本地与 CI 才等价。

osc8 一起钉是因为它走**同一条回退路径**读 `TERM_PROGRAM`，只是当前恰好没有测试被它咬到——留着就是下一颗。

**没有丢覆盖**：帧被 DEC 2026 序列包裹这件事由 `RenderLoopTest.synchronizedOutputWrapsOneCompleteFrame` 直接传构造参数开着断言，不依赖环境探测。

### B 组：1 × `TerminalMarkdownRendererTest` —— 读到了类加载顺序

原 PRD 猜的「顺序依赖的共享状态」对了，具体是：

`AnsiStyle.ENABLED` 是 `static final`、每个 JVM 只算一次；而 `TerminalMarkdownRendererTest` 在自己的**静态初始化块**里设 `paicli.render.color=false`。于是**谁先把 `AnsiStyle` 加载起来谁说了算**：

- 单独跑 → 它先加载 → 纯文本 → 绿
- 全量套件 → `InlineRendererTest` 先加载 → 带样式 → 红

判据 `line.length() <= 72` 把转义字节也算了进去。**实测**那根纯 ASCII 边框行：**72 列 / 85 字符**（`AnsiStyle.subtle()` 给它包了 `DIM`+`GRAY`+`RESET` 共 13 个字符）。

**最小复现**（不用跑全量）：

```
mvn test -Dtest=InlineRendererTest,TerminalMarkdownRendererTest -DskipTests=false \
    -Dsurefire.runOrder=alphabetical      # 修复前：73 tests / 1 failure
```

注意这个成因在仓库里**已经被写下来过两次**——`InlineRendererTest:357` 的注释甚至明说「这正是本仓库已有的那条的成因」，`InputBoxChrome:105` 也记了同一件事。两处都在，只是没人把它和这条红测试连起来。**下次遇到顺序依赖，先 grep 仓库里有没有人已经写过。**

**修法**（两件事，缺一不可）：

1. 删掉那个静态初始化块。它从不还原，**反向也污染**：先手时会把 `AnsiStyleTest` 里两条 `assumeTrue(AnsiStyle.isEnabled())` 变成静默 skip。测试要的是「不依赖色彩档位」，不是「把色彩按死」。
2. 判据改成**显示列数**：`new AttributedString(line).columnLength() <= 72`，断言前先剥 ANSI（`AttributedString.fromAnsi`）。

第 2 点顺手把判据变强了：`String.length()` 当宽度代理**两头都不准**——CJK 一个字占两列却只算一个字符（宽表能漏过去），带样式时转义序列又被算进来。改完之后这个类在 `-Dpaicli.render.color=true` 和 `=false` 下都是 13/13 绿。

## 为什么没有把 `paicli.render.color` 一起钉进 `${test.argLine}`

试过，**实测不行**：钉成 `false` → `1281 tests / 2 failures / 2 skipped`。

- 2 红：`ScreenTruthRegressionTest` 两条依赖彩色档位的横线字形（见 PRD「已知边界」）
- 2 skip：`AnsiStyleTest` 两条 `assumeTrue(isEnabled())` 被静默跳过

用一个假绿换一个真绿，不划算。色彩的顺序依赖已经在测试侧根治（不再有任何测试写这个全局），不需要再钉。

## 新增的两条守卫

`src/test/java/com/cafecli/render/RenderTestIsolationGuardTest.java`，照仓库既有的「可 grep 的验收」成例（`MainInputNormalizationTest.neitherTheAssemblyNorTheReplReadsTheTerminalItself`）：

1. `noTestSeizesTheGlobalColorSwitch` — 没有任何测试源码写 `setProperty("paicli.render.color"`
2. `surefirePinsTheCapabilityProbesThatSniffTheHostTerminal` — pom 里两个 flag 都在，且没有任何 `<argLine>` 绕过 `${test.argLine}`

断言的是**源文件本身**而不是行为，因为这两件事要防的正是**下一次**有人把它们改回去：那种改动不会让任何行为断言变红，只会让套件在别人的机器上、或换个执行顺序之后莫名其妙地红一条。

**两条都验过能红**（先破坏再跑，确认门是活的）：

- 把静态初始化块塞回 `TerminalMarkdownRendererTest` → 守卫 1 红，报出文件名
- 把 quick profile 的 `<argLine>` 改回字面量 `-Dfile.encoding=UTF-8` → 守卫 2 红
- 两处还原 → 2/2 绿

## 判据

- [x] B 组：污染源被指名（`AnsiStyle.ENABLED` 的 `static final` 快照 × `TerminalMarkdownRendererTest` 的静态初始化写全局），已修
- [x] A 组：5 条定性——**既不是「过时期望」也不是「真缺陷」**，是第三种：判据读了宿主环境。已修
- [x] `mvn test -Pquick` 在干净树上全绿（且在模拟 CI 的环境、反向执行顺序下同样全绿）
- [x] 隔离数量：**0**，所以不需要 `@Disabled` 理由链接
- [x] 新增守卫防复发，且验过能红
- [x] 沉淀进 `AGENTS.md`「验证」（不变式）与「当前状态与已知边界」（`NO_COLOR`）

## 留给 issue 02 的

01 已解除对 02 的阻塞。两条直接可用的结论：

- CI 的 runner **不要设 `NO_COLOR`**，否则 2 红 + 2 静默 skip。
- 02 要求的「断言 skipped = 0」现在有意义了：本次全部 6 种跑法都是 `Skipped: 0`，这是个真基线，不是碰巧。
