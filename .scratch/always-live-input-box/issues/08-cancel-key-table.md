# 08 — [[取消键]]表：ESC / Ctrl+C / Ctrl+D

Status: ready-for-agent

## Parent

`.scratch/always-live-input-box/PRD.md`（ADR 0014）

## What to build

定义并实现取消键的完整语义。07 之后 ESC 与 Ctrl+C 都成了普通键绑定、活在同一个 regime 里，因此它们的含义必须写成一张表，而不是靠「当前处于哪个模式」隐式推导。

一句话规则：**有回合在跑就停回合**。输入框只在没有东西可停时才被清空。

| | 回合运行中 | 空闲且框内有文字 | 空闲且框为空 | 模态开着（浏览层 / palette） |
|---|---|---|---|---|
| **ESC** | 取消当前前台回合 + [[附着]]任务、拒绝待决审批、丢弃未生效[[转向消息]] | 无操作 | 无操作 | **只对模态说话**（层内 ESC 只关层） |
| **Ctrl+C** | 同上，**框内文字保留** | 清空输入框 | 首次提示、约 2 秒内再次按下退出 | **与左边三列一致**，且**不关模态** |
| **Ctrl+D** | 无操作 | 无操作 | 退出 | 无操作 |

三个刻意的选择：

- **ESC 单一职责**——只做取消，空闲时无操作。它原先兼任的「清空输入」交给行编辑器内建的整行删除。理由是 ESC 的含义从此不依赖状态。
- **取消保留已键入文本**。用户中断异常携带部分输入行，据此把文本 seed 回下次读取。「先停下，然后按我说的做」必须是一个连续动作，取消若清空输入就破坏了它。
- **模态开着时两键分岔，理由是送达方式而不是约定**（ADR 0016 D3）。ESC 是一个**字节**，得由当前占着字节流的人读到，而[[模态租借]]期间那个人是模态自己——取消分类器根本看不到它，这正是 A13 明码标价的「回合期要取消得先关层」。Ctrl+C 是**信号**，不经字节流送达，因此不受租借影响：它在任何地方都保持同一个含义，一句话规则**零例外**。取消**不关层**——取消不需要主屏上的任何东西，把屏幕从正在读的人眼前抽走要另有理由（[[审批应答]]有，取消没有）。

取消范围与 CONTEXT.md 既有定义**一致，不得扩大**：只取消当前前台回合与仍**附着**的任务；**游离**任务不受影响，仍只经 `/task cancel` 终止。取消是协作式的，落在回合边界而非流式回答中途，并给用户即时反馈。

> **「不死」那一半已拆出为 issue 12，且不被本 issue 阻塞。** 它需要的东西与常驻读取器无关，而本
> issue blocked by 07；把一个正在毁掉用户会话的缺陷压在 tracker 里最大的 issue 后面是排期错误。
> 本 issue 保留完整的取消键表——包括拒绝待决审批、清空输入框、连按两次退出、ESC 的全部语义。

另需一个**会话级 SIGINT handler** 兜底（**由 issue 12 交付**），而它的覆盖面比原先写的大：JLine 的中断处理只在读取期间安装，斜杠命令处理等读取间隙仍是默认处理器（会杀进程）。**每个 raw-mode 模态都是这样一个间隙**——`enterRawMode()` 清 `ICANON/ECHO/IEXTEN/IXON/ICRNL/INLCR` 而**不清 `ISIG`**（`AbstractTerminal.java:194-203`），本仓库又**从未安装过任何 `Signal.INT` handler**（`terminal.handle(...)` 的调用点全是 WINCH）。所以今天 `main` 上：回合中按 Ctrl+O 进浏览层（A13，已交付并真机确认），在层里按 Ctrl+C，Cafecli 连同会话、历史、内存级[[任务]]一起死。palette 与 `InlineApprovalPrompter` 同理。A13 把这个间隙从「一瞬」变成了「用户可以待几分钟」。

**未验证，须实测而非假定**：07 之后浏览层永远跑在 `readLine` 内的 widget 里，会话级 handler 与 JLine 自己的 INT handler 可能同时在场；谁赢、以及挂起的 widget 里能不能浮出 `UserInterruptException`，尚未确立。

## Acceptance criteria

- [x] 表中十二个格子的行为逐一正确 —— `cli/CancelKeyTableTest`（13 条，S-B）+ `render/inline/ResidentInputRegimeTest` 的模态一列（3 条新增）
- [x] **浏览层开着 + 回合在跑时按 Ctrl+C**：回合停止、**浏览层保持打开**、层内内容继续跟随（回合停下来这件事在层里看得见）—— `ResidentInputRegimeTest.theCancellationIsVisibleInsideTheOverlayWithoutClosingIt`（去掉帧泵即红，说明它测的是提交日志→实时跟随那条路）
- [x] 浏览层 / palette / 审批期间按 Ctrl+C **不再杀进程** —— 浏览层：`ResidentInputRegimeTest.ctrlCInsideTheOverlayStopsTheTurnAndLeavesTheOverlayOpen`；palette：`InlineRendererTest.ctrlCInsideTheSlashPaletteStopsTheTurnAndLeavesThePaletteWaiting`；审批读键那个间隙已随审批改经[[输入框]]消失（`InlineApprovalPrompter` 已删除）
- [x] 层内 ESC 只关层，不取消回合（A13 不变）—— `ResidentInputRegimeTest.escInsideTheOverlayOnlyClosesTheLayerAndTheTurnKeepsRunning`
- [x] 回合运行中按 Ctrl+C：回合停止、输入框内已键入文本**原样保留** —— `CancelKeyTableTest.ctrlCDuringATurnStopsItAndKeepsWhatWasTyped`
- [x] 回合运行中按 ESC：回合停止、待决审批被拒绝、未生效转向消息被丢弃 —— `escDuringATurnStopsIt` / `escDuringATurnRejectsThePendingApproval` / `cancellingDiscardsSteeringThatNeverTookEffect`（后者摘掉 `discardSteering()` 即红）
- [x] 取消只波及前台回合与附着任务；游离任务继续运行，仍可经 `/task cancel` 单独终止 —— `cli/CancellationScopeTest.cancellingTouchesTheForegroundTurnAndItsAttachedTasksAndNothingElse`
- [x] 取消给出即时反馈，且实际停止发生在回合边界（流式回答不被中途截断）—— `CancellationScopeTest.cancellationLandsOnATurnBoundaryAndDoesNotTruncateTheStreamingAnswer`，带对照组 `theControlGroupWithNobodyCancellingRunsOnIntoTheNextIteration`
- [x] 空闲且框为空时首次 Ctrl+C 给提示、约 2 秒内再次按下退出；超时后重新计数 —— `ctrlCOnAnEmptyBoxHintsFirstAndTheSecondPressExits` / `theExitConfirmationReArmsAfterItsWindowPasses`
- [x] ESC 在空闲时无操作（不清空输入框）—— `escWhileIdleDoesNothingAndTheDraftSurvives` / `escWhileIdleAndEmptyDoesNothingAtAll`；widget 由 `cafecli-cancel-or-clear` 改名为 `cafecli-cancel`
- [x] 斜杠命令处理等读取间隙按 Ctrl+C 不再杀进程 —— issue 12 交付，`cli/SessionInterruptGuardTest`
- [x] 通过 06 建立的 seam 覆盖全表 —— 十二格全部经管道流驱动的 `InteractiveRepl`
- [ ] `mvn test -Pquick` 全绿 —— **1203 run / 1 failure**，唯一一条是既有的顺序相关 flake `TerminalMarkdownRendererTest.wrapsWideMultiColumnTableInsideTerminalWidth`（本 issue 之前的基线同样是它，单跑与 `-Pphase16-smoke` 下均绿）。本 issue 引入的 18 条全绿；`-Pphase16-smoke` 424/424 BUILD SUCCESS。按 07 / 10 的惯例不打勾。
- [x] 文档同步：AGENTS.md（[[取消键]]表整条重写 + [[信号兜底]]条 + `InteractiveRepl` 条的 widget 描述 + 已知边界一节）；CONTEXT.md 的[[取消键]]词条；README 的按键说明；启动提示改为「任务运行中按 ESC 或 Ctrl+C 取消当前任务」；ADR 0016 D3 补 Completed 小节

## 交付说明

- **一条被本表反转的既有回归**：`ResidentInputRegimeTest.ctrlCWhileAnApprovalIsWaitingStopsTheTurnWithoutAnsweringTheApproval`
  断言的是 issue 12 的「Ctrl+C 停回合但不拒审批」，而表里 Ctrl+C 在「回合运行中」那一格写的是**同上**。
  机制上的理由比对称更强：park 在审批 future 上的 worker 不会因为令牌被取消而醒过来，不拒掉它「回合停下来了」
  就不成立。该用例已改名为 `...StopsTheTurnAndRejectsTheApproval` 并反转断言。
- **三个入口一份实现**：`cli/SessionInterruptGuard` 从「SIGINT 兜底」长成**那条唯一的取消例程**
  （拒审批 + 丢转向 + 取消前台与附着 + 一句反馈 + 返回「有没有停下什么」），ESC widget、`readLine` 的
  `UserInterruptException`、模态自己认下的中断都调它；`InteractiveRepl` 因此改为接收它而不是裸的
  `CancellationRegistry`。
- **Ctrl+D 的活性守卫**：「回合运行中无操作」意味着读到文件尾要回去继续读，而**流本身结束**时那是个满速空转的
  循环。两者在 `readLine` 那头是同一个异常，区分它们的是节奏——5ms 内连着来两次即判定流断了、照常退出。
- **未在真机确认**（真机确认归 `.scratch/destructive-resize-epoch/issues/11-real-terminal-confirmation-pass.md`）：
  回合中 Ctrl+C 后草稿是否还在框里、空框连按两次是否干净退出、层里按 Ctrl+C 能否读到那行反馈且层不关。

## Blocked by

- `.scratch/always-live-input-box/issues/07-continuous-readline-and-approvals-through-box.md`
