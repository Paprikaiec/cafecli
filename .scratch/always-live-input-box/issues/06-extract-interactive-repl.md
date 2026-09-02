# 06 — 交互 REPL 从 CLI 入口抽成无终端依赖的可驱动单元

Status: ready-for-human

## Parent

`.scratch/always-live-input-box/PRD.md`（测试 seam S-B）

## What to build

把交互 REPL 从 CLI 入口类里抽出来，成为一个**无终端依赖、可被桩驱动**的单元。**纯重构，零行为变化**——这是 ADR 0005 对事件循环做过的同一件事（「须从 `Main` 抽出为无终端依赖的可驱动单元，否则不可测」），现在轮到输入 regime。

抽出的单元持有：读输入的循环、键绑定、提交路由（普通提交 / 斜杠命令 / 审批 drain）、以及回合期间的等待逻辑。CLI 入口退化为装配：构造终端、渲染器、行编辑器、事件循环、审批泵、取消注册表，然后把它们交给这个单元。

建立配套的测试 seam：用建在**管道流**上的 JLine 终端驱动它——从另一个线程写入字节，断言合成帧与由此产生的事件循环事件。Wave 0 已证明无头环境可以构造出非 dumb 的终端（显式指定类型与尺寸），因此这条 seam 成立，不需要真实 TTY。

本 issue **不改任何行为**。它存在的唯一目的是让 07 的 regime 切换发生时就能被断言，而不是事后补测。先用它把**现有**行为钉住：斜杠命令分派、未知命令报错不回退给 Agent、空输入跳过、提交后写回转写块、回合期间的等待与退出条件。

## Acceptance criteria

- [x] 交互 REPL 成为独立单元，可在无真实 TTY 的环境下构造与驱动（`cli/InteractiveRepl.java`）
- [x] CLI 入口只负责装配与启动，不再内联 REPL 主体逻辑（`Main.main` 少 543 行；斜杠命令体去 `cli/SlashCommandExecutor.java`）
- [x] 测试可通过管道流写入字节驱动该单元，并断言产生的事件循环事件（`InteractiveReplTest`，7 个用例）
- [x] 现有行为被测试钉住：斜杠命令分派、未知命令直接报错不回退给 Agent、空输入跳过、提交内容写回转写、回合期间等待到空闲后返回、ESC 取消前台回合
- [x] **零行为变化**：改造前后交互路径的可观察行为一致
- [x] `mvn test -Pquick`（810 个，仅剩既有的 2 处：resize 红测试 + markdown 宽表 flake）与 `mvn test -Pphase16-smoke`（300 个全绿）

## 落地说明

- **合成帧断言未做**：本 issue 的断言落在事件循环事件与渲染器端口调用上。帧级断言的现成设施（`TerminalScreenModel` / `TracingTerminal`）在 `render.inline` 包内且 pump 替身是包私有的；REPL 的帧形态又正是 07/09 要改的东西，所以帧断言留给 07。
- **仍留在 `Main` 的 REPL 相关物**：回合期控制键的分类纯函数（`readRuntimeControl` / `classifyRuntimeControl` / `classifyEscapeSequence` / `decideEscCancel` / `handleRuntimeDisplayControl` / `readInputBurst` / `RuntimeControl` / `EscapeSequenceType`）与转写块的排版助手（`printSubmittedInput` / `printSubmittedPrompt` / `redactSensitiveInput`）、`handleTranscriptCommand`。前一组是 07 的删除清单，就地留着让 07 一次删干净；后一组是「装配 + helper」，已有各自的测试。
- **顺带删除**：`defaultSpaciousPrompt`（恒返回 false 的死分支）与 `readPromptInput` 里那条不可达的 `println()`，以及它们的测试。
- **一处有意的统一**：抽取前 `/exit` 走「道别 + 关渲染器 + return」而 Ctrl+D 走「道别 + 停事件循环 + 关渲染器」；现在两条退出路径都由 `InteractiveRepl.sayGoodbye()` 道别 + 关渲染器，`Main` 在 `repl.run()` 返回后统一 `agentSession.stop()`。执行器是 daemon 线程且退出时会话已空闲，可观察行为不变。

## Blocked by

- `.scratch/always-live-input-box/issues/02-delete-plan-and-execute.md`
