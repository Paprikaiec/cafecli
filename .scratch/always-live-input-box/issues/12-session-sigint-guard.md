# 12 — 会话级 SIGINT 兜底：模态里按 Ctrl+C 不再毁掉会话

Status: ready-for-agent

## Parent

`.scratch/always-live-input-box/PRD.md`（ADR 0016 D3 的**前半**；后半即完整[[取消键]]表仍归 issue 08）

## What to build

**今天 `main` 上**：回合跑到一半按 Ctrl+O 进[[转写浏览层]]（A13，已交付并真机确认），在层里按 Ctrl+C，
Cafecli 连同会话、对话历史、内存级[[任务]]一起没了。`SlashPalette` 与 `InlineApprovalPrompter` 同理，
回合期的等待泵也同理。

成因是两条事实叠在一起：

- `enterRawMode()` 清 `ICANON / ECHO / IEXTEN / IXON / ICRNL / INLCR`，**不清 `ISIG`**
  （`AbstractTerminal.java:194-203`）——所以每个模态里 Ctrl+C 照常产生 SIGINT；
- 本仓库**从未安装过任何 `Signal.INT` handler**（`terminal.handle(...)` 的调用点全是 WINCH：
  `InlineRenderer.java:240 / 315 / 360`）——所以 SIGINT 落到 JVM 默认处理器。

JLine 只在 `readLine` 期间装它自己的 INT handler，出了 `readLine` 就是裸的。A13 把这个"读取间隙"从
一瞬变成了「用户可以待几分钟」，于是一个一直存在的洞变成了会被撞上的洞。

**装一个会话级 `Signal.INT` handler**，在装配期（`Main`）安装：

- **有前台回合在跑** → `CancellationRegistry.cancelForegroundAndAttached()`，并经 `Renderer.stream()`
  给出既有的那句即时反馈。范围与 CONTEXT.md 一致、**不得扩大**：只碰前台回合与仍[[附着]]的任务，
  [[游离]]任务不受影响。
- **没有可停的东西** → **什么都不做**（关键是不死）。
- **不关模态**（ADR 0016 D3）：取消不需要主屏上的任何东西。反馈经 `stream()` 进有序提交日志，
  [[转写浏览层]]的实时跟随会把它显示出来，所以"回合停下来了"这件事**在层里看得见**。

它与 JLine 的 handler 不冲突：`readLine` 期间 JLine 的覆盖在上面（行为不变，仍抛
`UserInterruptException`），出了 `readLine` 才轮到这个。

## 明确不做（留给 08）

- **拒绝待决审批**。取消键表里 ESC / Ctrl+C 都要连带拒审批，但 07 之前审批是 `InlineApprovalPrompter`
  阻塞读单键，从它下面把 pending 抽走是另一件事。本 issue 只保证不死 + 停回合。
- 空闲态的「连按两次退出」、清空输入框、ESC 的全部语义 —— 整张表归 08，且 08 仍 blocked by 07。

## 明码标价的代价

装了 handler 之后，**Ctrl+C 不再是卡死进程的逃生口**：MCP 启动卡住、工具卡死这类「没有回合在跑但也
回不来」的场合，Ctrl+C 会静默无事。这是本 issue 选定形态的已知代价（评估过「空闲时 re-raise 默认
处理器」的变体，未采用——那让规则带上条件分支）。剩余逃生口：`Ctrl+\`（SIGQUIT）、从另一个终端
`kill`。issue 08 的「空闲且框为空时连按两次 Ctrl+C 退出」落地后会把这个缺口收窄。

## Acceptance criteria

- [x] 回合运行中打开[[转写浏览层]]，层内按 Ctrl+C：**进程存活**、回合被请求取消、**浏览层保持打开**
      （`InlineRendererTest.ctrlCWhileTheOverlayIsOpenStopsTheTurnAndLeavesTheReaderInTheLayer`：
      层只被留在手边的 `q` 关掉）
- [x] 取消的反馈经提交日志进入转写，因此在**层内**可见（同一条用例：层的输出里读得到「已请求取消」）
- [x] 斜杠 palette 打开时按 Ctrl+C：进程存活
      （`InlineRendererTest.ctrlCInsideTheSlashPaletteStopsTheTurnAndLeavesThePaletteWaiting`）
- [x] 审批待决（`InlineApprovalPrompter` 正在读键）时按 Ctrl+C：进程存活
      （`InlineRendererTest.ctrlCWhileAnApprovalIsWaitingStopsTheTurnWithoutAnsweringTheApproval`，
      顺带钉住边界：审批**不**被拒，用户随后按的 y 仍生效）
- [x] 回合期等待泵中按 Ctrl+C：进程存活、回合被请求取消（与 ESC 同一条取消通路）
      （`SessionInterruptGuardTest.ctrlCDuringAForegroundTurnStopsTheTurnAndTheSessionKeepsServing`）
- [x] 空闲且在 `readLine` 里按 Ctrl+C：**行为与今天完全一致**（JLine 的 handler 仍在上面，
      `UserInterruptException` → REPL `continue`）——本 issue 不得改动空闲态
      （`SessionInterruptGuardTest.jlineOwnsTheInterruptWhileItIsReadingAndTheSessionGetsItBackAfterwards`，
      在**真的** `LineReaderImpl` 上实测：JLine 存下前一个 handler、退出时还原）
- [x] 取消只波及前台回合与附着任务；游离任务继续运行，仍可经 `/task cancel` 终止
- [x] 渲染线程从不因此读终端；signal 线程只经 `Renderer.stream()` 说话，不直写终端
      （层内用例断言中断那一刻终端字节数增量为 0）
- [x] 经 06 建立的 seam 覆盖：注入一个假的 signal 触发点，断言产生的取消事件与不退出
      （管道流驱动的 `InteractiveRepl`，但放在 `SessionInterruptGuardTest` 里——见下文）
- [ ] `mvn test -Pquick` 与 `mvn test -Pphase16-smoke` 全绿 —— **见下文实测**，两处既有噪声未清
- [x] 文档同步：AGENTS.md「当前已知边界」里这条缺陷的条目改为已修；README 按键说明补 Ctrl+C

## 实测结果（2026-08-27）

- `mvn -o test -Pphase16-smoke`：**399/399 全绿**（HEAD 的 396 + 本 issue 新增 3 条）。
  注意需 `env -u TERM_PROGRAM`：`TERM_PROGRAM=ghostty` 会让 `TerminalCapabilities.supportsSynchronizedOutput`
  对 mock 终端返回真，5 条逐字节用例因多出 `ESC[?2026h/l` 而红。这与本改动无关（新增用例不断言字节），
  但说明**这批字节级断言依赖运行环境的 `TERM_PROGRAM`**。
- `-Pquick`（`-Dtest='!InteractiveReplTest'`）：1262 条，**1 条失败**——
  `TerminalMarkdownRendererTest.wrapsWideMultiColumnTableInsideTerminalWidth`，已知的顺序相关 flake
  （单独跑该类 13/13 全绿），与本改动无关。
- **`InteractiveReplTest` 整类跑不完，是既有缺陷**：`aPlainMessageIsPostedToTheEventLoopAsAUserSubmit`
  与 `theSubmittedInputIsWrittenBackToTheTranscriptBeforeTheTurnRuns` 把 `^D` 排在回合**之前**写进管道，
  而回合期 `readRuntimeControl` 会把非 ESC 字节静默 drain 掉（`Main.java:538-546`），随后的 `readLine`
  再也等不到 EOF。实测：这两条各自单独跑分别在 300s / 180s 超时；其余四条一起跑 0.441s 全绿。
  本 issue 因此没有把用例加进该类（加了也永远轮不到跑），而是带一个自己的管道 rig。修法是 3 行的
  测试改动（回合跑完再写 `^D`），但不属本 issue，留给 07 或单独一轮。
- **真 pty 实测**（一次性探针，未提交；ghostty pty + `script` + 真 `kill -INT`，n=1 对照）：
  装上兜底 → 令牌在信号那一刻被取消、JVM 存活并正常退出；不装 → 进程当场死亡。
  测量陷阱：启动时继承 `SIG_IGN` 的进程（无作业控制的后台任务）**根本装不上** INT handler
  （HotSpot 保留继承来的忽略），这样跑出来的实验什么都没测到。

## 给 issue 07 的一条实测结论

widget 阻塞在自己的 `terminal.reader().read()` 里时按 Ctrl+C：JLine 中断读线程，`read()` 抛
`InterruptedIOException`，**会话级兜底一个字节都看不到**；widget 吃掉这个异常之后 `readLine` 继续读，
`UserInterruptException` 也**不会**浮上来（`SessionInterruptGuardTest.aWidgetBlockedOnItsOwnReadIsInterruptedByJlineAndNotBySessionGuard`）。
今天没问题——回合期的浏览层由等待泵打开、不在 `readLine` 里。但 07 让 reader 常驻之后，兜底处处被盖住，
ADR 0016 D3 承诺的「层内 Ctrl+C 停回合」必须由浏览层自己的读循环认下这个中断（或由 07 串接 handler）。

## Blocked by

无。这是对**已交付代码**的修复，缺陷现在就活在 `main` 上。完整取消语义仍归 08（blocked by 07）。
