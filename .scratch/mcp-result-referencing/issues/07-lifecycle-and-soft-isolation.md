# 07 — 生命周期 + 软隔离

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/mcp-result-referencing/PRD.md`（ADR 0009）

## What to build

给结果目录接上会话生命周期与跨[[任务]]隔离。

- 新增窄口径 `ToolRegistry.clearConversationScopedState()`：清 active MCP tools（[[已激活工具集]]）+ 对应 scope 的结果目录；**不**清 MCP 连接、discovered tool catalog、长期记忆、后台任务。
- `/clear` 调它清主 scope（`main`）；[[任务]]回收清对应 `task_N` scope；进程**正常退出**删整个 `{session}/` 目录；**不做 startup GC**（崩溃孤儿留用户手动删，整个 `.cafecli/tool-results/` 一删即净）。
- 软隔离：子任务的 `ToolRegistry` 用自己的 `{scope}=task_N`；**不向子任务 surface 父任务结果路径**（`ForegroundAgentSpawner` 在 `createChildRegistry()` 后设 scope label）。物理上同 session 目录可达，但不主动暴露。

可演示（测试）：`AgentClearHistoryTest` 断言 `/clear` 删主 scope 目录、且不动 MCP 连接/catalog/长期记忆/后台任务；`ChildToolRegistryTest` 断言子任务 scope 隔离、拿不到父任务结果路径；正常退出删 `{session}/`。

## Acceptance criteria

- [ ] `clearConversationScopedState()` 清 active MCP tools + 本 scope 结果目录，不清连接/catalog/长期记忆/后台任务
- [ ] `/clear` → `main` scope；任务回收 → `task_N` scope；正常退出 → 删整个 `{session}/`
- [ ] 不做 startup GC
- [ ] 软隔离：子任务用 `task_N` scope，不 surface 父任务结果路径
- [ ] 测试（seam）：`AgentClearHistoryTest`（/clear 清目录 + 保留长生命周期态）、`ChildToolRegistryTest`（scope 隔离）

## Blocked by

Issue 02（引用化 tracer bullet）
