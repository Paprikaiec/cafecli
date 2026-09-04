# 02 — 每任务 ToolRegistry + 注入式共享服务 → 前台并行 fan-out

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/async-task-substrate/PRD.md`（决策见 ADR 0007）

## What to build

让每个[[任务]]拥有自己的 `ToolRegistry`，使并发的前台派生互不串台。重构 registry 构造：昂贵的共享机器**显式注入**（MCP 连接、LSP 进程、网络 provider、进程级[[文件互斥锁]]、审计日志、长期记忆），会话相邻的可变状态（LSP 待注入诊断缓冲、Skill 上下文缓冲、模型选择、短期历史）**每任务私有**。随后一条 assistant 消息里的多个 `spawn_agent` 调用经现有[[工具批次]]并行执行、各自隔离。跨任务对同一文件的写入仍由文件互斥锁串行（ADR 0001）。

可演示：一条消息里派生 3 个研究子 Agent，它们并发执行且 LSP/Skill/模型状态互不渗透。

## Acceptance criteria

- [ ] 每任务构造/持有自己的 `ToolRegistry`；共享服务靠注入而非重建（不出现每任务重连 MCP / 重开 LSP）
- [ ] LSP 待注入诊断、Skill 缓冲、模型/provider 选择每任务私有；无跨任务串台
- [ ] 一条 assistant 消息里 N 个 `spawn_agent` 经[[工具批次]]并行执行
- [ ] 不同任务对同一文件的并发写入经 `FileMutationLocks`（ADR 0001）串行，无丢失更新
- [ ] 测试：`CountDownLatch` + 峰值并发 `AtomicInteger` 证明 ≥2 任务并发；另有测试证明任务 A 的 LSP/Skill 状态不出现在任务 B

## Blocked by

- #01
