# 06 — 工具批次聚合预算 B

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/mcp-result-referencing/PRD.md`（ADR 0009）

## What to build

补一个批次级防线：单个结果各自 < T 时也可能因并行而合起来撑爆本回合。在 [[工具批次]]（同一 assistant 消息里并行执行、上限 4 的一组 tool call）层面加聚合预算。

- `ToolRegistry` 在一个工具批次执行后，统计本批 MCP 结果的内联字符总量；若 > **B=125k chars** → 对本批 MCP 结果按大小降序落盘（复用 `McpResultPersister`）直到总量低于 B。
- 仅治理 MCP 结果（不含内置工具输出）。硬编码 B，后续配置化。
- 与 02 的单结果阈值 T 互补：T 管单个、B 管批次。

可演示（测试）：多路并行返回各自 < T 但合计 > B 的 MCP 结果 → 断言最大者被落盘引用直到总量 < B；合计 < B 时全内联、无落盘。

## Acceptance criteria

- [ ] 一个工具批次内 MCP 结果内联合计 > B=125k → 按大小降序落盘直到 < B
- [ ] 仅治理 MCP 结果；单个 <T 但批次超 B 的结果会被落盘引用
- [ ] 合计 < B 时不额外落盘
- [ ] 测试（seam）：`ToolRegistryTest` / `InMemoryTransport` 批次场景断言最大者落盘、总量收敛

## Blocked by

Issue 02（引用化 tracer bullet）
