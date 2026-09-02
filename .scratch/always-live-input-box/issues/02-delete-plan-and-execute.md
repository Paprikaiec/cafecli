# 02 — 删除 Plan-and-Execute，`/plan` 留墓碑

Status: ready-for-agent

## Parent

`.scratch/always-live-input-box/PRD.md`（ADR 0015）

## What to build

完整移除 Plan-and-Execute 模式。它与 ADR 0004 已退役的 `/team` 是同一物种（模型之外的确定性编排脚手架），且是仓库里唯一还自持终端输入的路径——占住主线程 + raw mode 轮询 ESC + 单键读评审决定 + 嵌套二次 `readLine`，与后续「终端按键只有一个消费者」正面冲突。

删除范围：Plan 执行代理、规划器与执行计划 / 任务模型、Plan 评审输入解析、CLI 的 `/plan` 命令类型与 plan 模式状态位、plan 与 planner 两份模式提示词及其 `PromptMode` 常量、上述全部对应测试。

`/plan` **不落进「未知命令」**：保留墓碑输出，说明该模式已移除并指向 ADR 0015。README 长期宣传过它，用户需要一句解释而不是一个错误。

删除后随之失去最后调用点的按键助手一并清掉：单键读取、预填充输入读取、转义输入读取，以及仅服务它们的按键结果 / 预填充结果记录类型；提交输入的记录类型收敛为纯字符串（它的「已取消」变体只为 plan 模式的 ESC 取消而存在）。注意**保留** `readInputBurst` / `classifyEscapeSequence` / `EscapeSequenceType`——它们还有回合期轮询这个调用点，在 07 里才退役。

## Acceptance criteria

- [ ] Plan 执行代理、规划器、执行计划与其任务模型、Plan 评审输入解析及全部对应测试不再存在
- [ ] `/plan` 输入后打印墓碑说明（含「已移除」与 ADR 指引），且不被当作未知命令，也不回退给 Agent
- [ ] plan / planner 两份模式提示词资源与其 `PromptMode` 常量移除
- [ ] 单键读取、预填充输入读取、转义输入读取及其结果记录类型不再存在；提交输入类型不再有「已取消」变体
- [ ] `readInputBurst` / `classifyEscapeSequence` / `EscapeSequenceType` 仍存在且仍被回合期轮询使用（本 issue 不动它们）
- [ ] 无人值守路径不受影响：`run --file` 与 `--strategy=react` 行为不变
- [ ] `mvn clean package` 通过，`mvn test -Pquick` 全绿
- [ ] 文档同步：AGENTS.md 的双执行路径表、硬规则 #3（Plan 审阅交互联动）、验证路径表中的执行计划测试行；README；ROADMAP 状态

## Blocked by

None - can start immediately
