# 10 — [[转向消息]]：迭代边界注入 + 回合结束兜底 + 即时回显

Status: done（2026-08-28；ADR 0016 D5 有交付记录。**未在真机确认**——归
`.scratch/destructive-resize-epoch/issues/11-real-terminal-confirmation-pass.md`）

## Parent

`.scratch/always-live-input-box/PRD.md`（ADR 0014）

## What to build

让回合运行期间提交的输入**注入当前回合**，而不是排队等本回合跑完。这是常驻输入框真正的价值——用户能在模型跑偏时纠正它，而不是眼睁睁看完一个已知是错的回合。

注入点是 ReAct 迭代边界：与 LSP 诊断、PostToolUse hook 上下文**同一条前置注入通道**，作为它们的第三个兄弟，从一个并发槽 drain，形态与既有的 hook 上下文 flush 一致。消息在下一次 LLM 调用前作为 user 消息生效——粒度与[[取消键]]相同，原因也相同：流式回答不可中途打断。

三件必须一起做的事：

1. **回合结束兜底**。回合若在下一个迭代边界到来前就结束（模型给出最终答案、无工具调用），未 drain 的转向消息将永远不会生效。此时必须把它重新投递为普通的用户提交事件。**没有这条，消息会静默消失——那比原来的「被吞掉」更糟**，因为用户以为它被接受了。
2. **即时回显**。提交瞬间就把它作为 `›` 块写入转写并进入[[保留窗口]]，标出它落在回合的哪个位置。若无回显，用户按下 Enter 后可能十几秒看不到任何反应。

   **两条约束，都不是可选的：**

   - **回显必须是源形态的 `SubmittedPromptItem`，不是烘好的行**（ADR 0010 修订 A8b）。提交块的底纹补齐到整行，按提交时的宽度烘死后，窄化重画会留一截残行。用既有的 `SubmittedPromptItem`，由重置在新宽度重新渲染。
   - **入队与 `ReplayLog.append` 必须成对，走那条唯一的有序队列**（A8b）。分开写就会让缩放重排提交顺序。

3. **[[保留窗口]]里的位置是一段源，不是一个条目**（ADR 0016 D5，本 issue 的**结构性前提**）。`ReplayLog.append` 按身份幂等、位置钉在**第一次**提交那一刻，而流式答案在它第一行落回卷时就进了日志。于是回合中途提交的转向消息会排在那个答案的**全部**输出之后。

   这不只是缩放后的现象：[[转写浏览层]]渲染条目用的是 `renderRetained`，也就是答案的**全部**样式行，所以**层内无条件复现**——而浏览层恰恰是用户去问「我那句纠正落在哪儿」的地方。本 issue 的价值全在位置上，所以这条必须在本 issue 内解决，不能挂账：日志位置改记 `(源, 已提交区间)`，被打断的答案占两个位置、打断它的那条夹在中间。两个消费者（`ReplayWindow.select` 与 `OverlayDocument`）随之调整；同一个答案的两段之间**不得**冒出分隔空行，它读起来仍应是一个块。
4. **取消时丢弃**。ESC / Ctrl+C 取消回合时，尚未生效的转向消息一并丢弃（08 已在取消语义中声明，此处提供丢弃能力）。

已知并接受：快照锚定的是回合的**原始**输入，被转向过的回合与该标签不再完全对应，`revert_turn` 因此更粗——这是 ADR 0005 已接受的 effort 级回退再放宽一档，不在本 issue 内解决。

## Acceptance criteria

- [x] 回合运行期间提交的内容在**下一次 LLM 调用前**出现在对话历史中，形态为清晰标记的 user 消息
      （`AgentSteeringTest.aMessageSteeredMidTurnIsInTheConversationBeforeTheNextLlmCall`；标记
      `[用户转向 / 回合中提交]`，并断言它**不在**提交那一刻已在路上的那次请求里）
- [x] 注入发生在迭代边界，与 LSP 诊断 / hook 上下文走同一条通道，不破坏它们各自的注入
      （`steeringDoesNotDisplaceTheLspDiagnosticsOnTheSameChannel` 与
      `steeringDoesNotDisplaceThePostToolUseHookContextEither`——两条兄弟各一次**同回合并存**断言，
      不是靠既有回归没红）
- [x] 回合在下一个迭代边界前结束时，未生效的转向消息被重新投递为普通用户提交，**不丢失**
      （`aSteeringMessageThatNeverReachedABoundaryBecomesAnOrdinarySubmission` +
      `InteractiveReplTest.aSubmissionDuringARunningTurnSteersItInsteadOfQueueingBehindIt` 的后半）
- [x] 提交瞬间即在转写中出现 `›` 块，并进入保留窗口（转写浏览层可见）
      （`SteeringPositionTest.theSteeringEchoAppearsInTheOverlayWhereItInterruptedTheAnswer`；
      REPL 侧：提交后立刻出现在转写里且**没有**开启第二个回合）
- [x] 回显是 `SubmittedPromptItem`（源形态），缩放后按新宽度重排，不留半行底纹
      （`ScreenTruthRegressionTest.theSteeringEchoIsAlsoRepaintedAtTheNewWidthNotTheOneItWasSubmittedAt`）
- [x] **转向消息在[[转写浏览层]]里出现在答案被打断的那个位置**，而不是整个答案之后——不需要缩放即可断言，开层直接看
      （同上；改动前实测为红，报的正是「correction 排在整个答案之后」）
- [x] **同一条被打断的答案在层内与重画界里都读作一个块**：两段之间无分隔空行、无重复行、无缺行
      （`anInterruptedAnswerPaintsExactlyWhatTheSameAnswerPaintsUninterrupted` /
      `...ReadsAsOneBlockInTheOverlayToo`——判据带控制组：把转向消息从日志里删掉后必须与
      「同一个答案没被打断过」逐行相等）
- [x] 缩放之后屏上的提交行序列与缩放之前逐行相等（A8b 的既有不变式，转向消息不得破坏它）
      （`ScreenTruthRegressionTest.aResizeDoesNotReorderAnAnswerInterruptedByASteeringMessage`；
      实测把日志改回「一条目一位置」即红）
- [x] 取消回合时未生效的转向消息被丢弃，不会泄漏到下一回合
      （`cancellingATurnDiscardsSteeringThatNeverTookEffect`；REPL 的两条取消入口共用
      `cancelRunningTurn` → `AgentSession.discardSteering()`）
- [x] 同一回合内多次转向按提交顺序生效（`severalSteeringMessagesInOneTurnTakeEffectInSubmissionOrder`）
- [x] 空闲时提交仍走普通提交路径，行为不变（`thereIsNothingToSteerWhenNoTurnIsRunning` +
      `InteractiveReplTest` 既有的普通提交 / 斜杠命令 / 审批应答用例全绿）
- [x] 通过桩 LLM 客户端的事件循环 seam 覆盖上述各条（无终端、确定性）——`AgentSteeringTest`，7 条，无 sleep
- [ ] `mvn test -Pquick` 全绿 —— **未打勾**：1185 条里 1 条失败，是**既有的**顺序相关 flake
      `TerminalMarkdownRendererTest.wrapsWideMultiColumnTableInsideTerminalWidth`（单独跑 13/13 绿、
      在 `-Pphase16-smoke` 里同一个类全绿；本 issue 开工前的基线就是 1171 条 / 同一条失败）。
      与本 issue 无关，按 issue 07 的先例不打勾。
- [x] 文档同步：AGENTS.md 中事件循环与 ReAct 注入通道的描述（另加有序提交日志「位置是一段源」、
      `retainedLogSnapshot` 语义、取消键丢弃转向；CONTEXT.md 的[[转向消息]]词条；ADR 0016 D5 交付记录）

## Blocked by

- `.scratch/always-live-input-box/issues/07-continuous-readline-and-approvals-through-box.md`
