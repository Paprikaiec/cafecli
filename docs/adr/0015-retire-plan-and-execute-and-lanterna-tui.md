# 退役 Plan-and-Execute 与 Lanterna TUI（ADR 0004 的续篇）

ADR 0004 以「确定性编排脚手架 → 主 Agent 自主派生」为由退役了 `/team` + `AgentOrchestrator`。Plan-and-Execute（`/plan`）是同一物种的最后一个：模型之外的确定性规划 + DAG 执行。它同时是仓库里**唯一**还自持终端输入的路径——`runWithCancelSupport` 占主线程 + raw mode 轮询 ESC，`createPlanReviewHandler` 用 `readSingleKeyFromTerminal` 单键读，外加一次嵌套 `readLine("补充> ")`——与 ADR 0014 的「常驻 `readLine`、终端按键只有一个消费者」正面冲突。Lanterna 全屏 TUI（`tui/`，`CAFECLI_TUI=true` 开启）是另一个 `PlanExecuteAgent` 消费者，且是 ADR 0014 正在重建的交互模型的第二份实现。

## 决策

**完整删除 `/plan` 与 `tui/`，不做迁移、不留降级路径。**

- 删 `PlanExecuteAgent`、`plan/`（`ExecutionPlan` / `Planner` / `Task`）、`PlanReviewInputParser`、`CliCommandParser.SWITCH_PLAN`、`prompts/modes/plan.md` 与 `planner.md`、`PromptMode.PLAN` / `PLANNER` 及对应测试。
- 删 `tui/` 整包与 `Main` 的 `TuiBootstrap` 分支；`RendererFactory.Mode.LANTERNA` 随之收敛（它本就已经 `yield new PlainRenderer()`）。`phase16-smoke` profile 里的 `TuiBootstrapTest` 一并摘除。
- `/plan` 保留**墓碑**：打印「该模式已移除（见本 ADR）」，而不是落进「未知命令」——README 长期宣传过它。

删除的连带收益是自动的：`readSingleKeyFromTerminal` / `readPrefillInputFromTerminal` / `readEscapeInput` / `readInputBurst` / `classifyEscapeSequence` / `EscapeSequenceType` / `KeyReadResult` / `PrefillResult` 的调用点在 `/plan` 之外只剩 ADR 0014 一并退役的 `readRuntimeControl`。故有可 grep 的验收条件：**改完后 `Main.java` 里 `terminal.reader()` 与 `enterRawMode()` 出现零次。**

## 被否决的方案

- **把 `/plan` 迁到事件循环**（评审决定像[[审批应答]]一样 park 在 future、经[[输入框]]提交）：技术上可行且比看上去小——`PlanReviewInputParser` 的行输入路径本就存在，只删单键路径。否决理由不是成本，是它保留了一个 ADR 0004 已判定应由主 Agent 自主派生取代的执行模型，让每次交互改动都要验证两条主路径。
- **只摘 `tui/` 的 plan 分支、保留该包**：留下一个 env var 后面的、无人维护的第二前端；ADR 0014 之后的每个交互决定都要么复制一遍，要么让它继续漂移。

## 后果

- 删除面：生产 1,679 行（`PlanExecuteAgent` 892 / `ExecutionPlan` 338 / `Planner` 280 / `Task` 129 / `PlanReviewInputParser` 40）+ 测试 527 行（5 个文件）+ `tui/` 整包。文档需同步 AGENTS.md（双执行路径表、硬规则 #3、验证路径表的 `ExecutionPlanTest` 行）、README、ROADMAP 状态。
- **这不是放弃「plan mode」这个产品概念**。对标的 Claude Code 里 plan mode 是一种**权限模式**（限制为只读工具、先出计划、确认后执行），不是 DAG 执行器。真要做该形态的对齐，落点是 ReAct 之上的 `ApprovalPolicy` / [[生命周期 Hook]]，substrate 已具备——与这里删掉的实现无关。
- ROADMAP 中 plan 的提及均为已交付期次记录与远期重写设想（Spring AI / LangGraph4J），无在途项依赖。
- `run --strategy` 本就只接受 `react`（`RunCommand.java:60-62`），故无人值守与 SWE-bench 路径不受影响——被删的是交互专属、未被任何评测覆盖的代码。
