# 弃用 /team 确定性编排，统一为主 Agent 自主异步编排

Cafecli 原有三条编排路径：ReAct（`Agent`）、Plan-and-Execute（`PlanExecuteAgent`，`/plan`）、Multi-Agent 确定性编排（`AgentOrchestrator`，`/team`）。在引入「会话内异步任务（Task）substrate」、并让主 Agent 成为该 substrate 之上的自主异步编排者之后，`/team` 的确定性 DAG 编排（规划 → 依赖批次 → worker → reviewer → 重试）失去了独立存在价值。

## 决策

弃用 `/team` 与 `AgentOrchestrator`。主 Agent 在异步任务 substrate 之上自主决定派生 / 续派 / 并行，成为**唯一**的多 Agent 编排模型。`/plan` 保留（价值点不同：可审阅的单 Agent 计划）。substrate（派生 / 续派 / 任务注册表）保留。

## 被否决的方案：把 /team 留作确定性「策略」

曾决定保留 `/team` 作为确定性编排策略，理由是「SWE-bench 等无人值守评测需要可预测、可测试的编排」。否决原因：在评测里启用确定性编排，等于人为给模型注入「先规划再执行」的先验脚手架，**测的是脚手架而非 agent 自身能力**，本身就是评测完整性问题——SWE-bench 应跑 `--strategy=react`，让模型自行决定是否并行探索。确定性编排的其余优势（成本可控、可单测、可复现）要么能在自主模型里用并发上限 + prompt 约束复现，要么因 worker 本身非确定而站不住脚；唯一独有的「review → 重试」质量闸退化为 prompt 级的**验证纪律**（与 Claude Code 一致）。

关键点：**弃用不是烧桥**。substrate 保留，若日后出现真实**用户级**「确定性并行 + 评审」诉求，可在 substrate 之上以数百行重建一个确定性 driver。

## 后果

- `AgentOrchestrator` 退役；AGENTS.md「三条路径」更新为两条（ReAct-含编排、Plan）。
- SWE-bench 走 `--strategy=react`；原 reviewer / 重试的价值迁移到验证纪律（continue-vs-spawn + 「证明可用、别盖橡皮章」）。
- 相邻决策（异步任务 substrate 取代 `DurableTaskManager`、REPL 事件循环 + push 再入）将分别记录。
