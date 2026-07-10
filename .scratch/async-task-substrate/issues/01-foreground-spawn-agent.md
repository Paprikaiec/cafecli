# 01 — 前台 spawn_agent（tracer bullet）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/async-task-substrate/PRD.md`

## What to build

给主 Agent 一个 `spawn_agent` 工具，派生一个自治子 Agent（一个[[任务]]），入参 `{description, prompt}`。子 Agent 在派生它的那一轮内**同步前台**跑到结束，拥有自己独立的对话历史；只把它的**最终结果文本**（+ 简短状态）作为工具结果回灌（result-only，不灌中间工具调用/推理）。新增内存级 `TaskRegistry` 追踪任务生命周期（运行 → 完成/失败）。本切片在当前架构上即可工作（Java 17、无事件循环），子 Agent 在派生轮内跑完。

可演示：让主 Agent 派生一个研究子 Agent，它跑完、报回结果，主 Agent 把结果总结给用户。

## Acceptance criteria

- [ ] `spawn_agent({description, prompt})` 注册为内置工具，入参走 JsonNode/DTO（遵循 ADR 0003），对主 ReAct Agent 可见
- [ ] 调用即派生一个有独立对话历史与独立预算的子 Agent，跑标准 agent 循环到结束
- [ ] 仅子 Agent 的最终结果文本（+ 简短状态）作为工具结果返回；其中间工具噪声**不进**主 Agent 上下文
- [ ] `TaskRegistry` 以稳定 `task_id` 记录任务，并发生 运行 → 完成（或失败）状态流转
- [ ] 在当前架构（Java 17、无事件循环）下工作，子 Agent 在派生轮内执行
- [ ] 测试（最高 seam）：用桩 `LlmClient` 驱动；断言主对话收到结果、注册表显示完成、子 Agent 工具噪声不出现在主历史

## Blocked by

None - can start immediately
