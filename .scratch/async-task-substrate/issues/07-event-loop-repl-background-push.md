# 07 — 事件循环 REPL + 后台派生 + push

Status: ready-for-human
Type: HITL（REPL / JLine 集成需设计评审）

## Parent

`.scratch/async-task-substrate/PRD.md`（决策见 ADR 0005）

## What to build

把交互 REPL 改造为**事件循环**。将回合执行器 + 主对话 + 事件队列 + `TaskRegistry` 抽出为**无终端依赖的 `AgentSession`**（即测试 seam）：主线程退化为**输入泵**（常驻读取），单一**串行执行器**独占主对话并跑回合；`UserSubmit` 与 `TaskDone` 都是事件、在回合边界 drain。新增**后台派生**：`spawn_agent({background:true})` 立即返回 `task_id`、本轮继续；后台任务完成时投递 `TaskDone` 唤醒执行器跑一个自治回合，把结果作为带标签的 `<task-notification>` user 角色消息注入，并经 `printAbove` 在输入行上方渲染。

可演示：派生一个后台 agent，在提示符继续打字，其结果被 push 进来并被处理，无需重新提问。

## Acceptance criteria

- [ ] `AgentSession`（含 `TaskRegistry`）可无终端构造，经 `postEvent(UserSubmit|TaskDone)` 驱动，`LlmClient` 可注入、通知 sink 可捕获
- [ ] 主线程 = 输入泵；单一串行执行器是主对话历史的唯一写入者
- [ ] `spawn_agent({background:true})` 立即返回 `task_id`；派生轮不阻塞地继续/结束
- [ ] 后台任务完成时其结果被执行器作为自治回合处理；输出经 `printAbove` 渲染且不破坏正在编辑的输入行
- [ ] push 结果为带标签的 `<task-notification>` user 角色块，携带 result + usage（result-only）
- [ ] 回合边界上多个待处理 `TaskDone` 被 drain 进同一回合
- [ ] 测试（headless seam）：用桩 `LlmClient` 投递 `UserSubmit` + `TaskDone`，断言主对话 + 捕获到的通知 + 注册表快照；并发用 latch/峰值模式

## Blocked by

- #01
- #02
- #06
