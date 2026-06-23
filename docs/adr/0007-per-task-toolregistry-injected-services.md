# 每任务独立 ToolRegistry，共享服务靠显式注入

[ADR 0005](0005-async-task-substrate-event-loop.md) 下子任务真正并发，而当前所有 SubAgent 共享同一个 `ToolRegistry`。该对象持有若干会话相邻的**可变**状态：待注入 LSP 诊断缓冲（`flushPendingLspDiagnostics` 是单一共享 buffer）、`SkillContextBuffer`、`currentModel`/`currentProvider`。并发任务下这些会**跨任务串台**——任务 A 触发的 LSP 诊断被任务 B 的回合 flush 进 B 的对话；两个任务选不同模型时互相覆盖。`/team` 同步执行侥幸躲过，并发任务躲不过。

## 决策

**每任务独立 `ToolRegistry`**，会话相邻的可变状态（LSP 诊断缓冲、Skill 缓冲、模型选择、短期历史）每任务私有；昂贵的共享机器**显式注入**而非各自重建：MCP 连接、`LspManager` 进程、网络 provider、进程级[[文件互斥锁]]（ADR 0001，本就是静态）、审计日志、长期记忆写入。原则一句话：**默认隔离，共享靠显式注入。**

## 被否决的方案

- **保留单一共享 `ToolRegistry`，把racy字段按任务 id 改成 map**（共享默认 + 外科打补丁）。否决：god-object 持续生长，将来每新增一个可变字段都是一个"忘了按任务隔离就跨会话泄漏"的隐患——正是最难复现的一类 bug。隔离应是默认而非补丁。
- **完全 per-Task、什么都不共享**：行不通——不能为每个任务重连 MCP server、重开 LSP 进程。共享昂贵服务是注入式设计的成因，不是可选项。

## 后果

- `ToolRegistry` 构造须从"自己 new 出 LSP/Snapshot/网络"改成"接收注入的共享服务"，是一次实打实的重构。
- 共享集合变小、显式、可审计（MCP/LSP/网络/锁/审计/长期记忆）；其余会话相邻状态私有，新增工具字段默认不泄漏。
- 快照（`SnapshotService`）不归此隔离——它是单一共享工作区的时间线，按 [ADR 0005](0005-async-task-substrate-event-loop.md) 锚定主回合边界，不做 per-Task。
