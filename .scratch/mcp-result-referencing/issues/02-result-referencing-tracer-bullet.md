# 02 — 引用化 tracer bullet：structuredContent 保留 + 大结构化结果落盘 + 有界引用

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/mcp-result-referencing/PRD.md`（ADR 0009）

## What to build

打通 [[MCP 结果引用化]] 的最小端到端管线：一个 MCP 大结构化结果从 server → 落盘 → 模型只见有界引用。本切片刻意只做**最小引用**（路径 + 大小 + 类型），schema 签名与取样 digest 留给 03。

- `McpCallToolResult` 新增 `JsonNode structuredContent`；`toToolOutput()` 产 typed blocks（`text` / `image` / `structured_json`），DTO 保持纯、不碰 fs、不脱敏、不截断。
- `ToolOutput` 兼容演进：保留 `text()`；新增 typed block 列表 + `renderTextForLlm()`（对**已变换** blocks 做纯 flatten）。
- 新增 `McpResultPersister`（唯一碰 fs）：把结构化结果 pretty-print 落盘到 `.cafecli/tool-results/{session}/{scope}/{tool_call_id}.json`，`wx` 幂等（同 id 不重写），持 session+scope。`{session}` 复用现有 `sessionId`，`{scope}` 默认 `main`。
- `ToolRegistry`（持 session+scope）编排 MCP 执行路径：`structured_json` 的 compact JSON ≥ **T=25k chars** → persister 落盘、block 替换为 `mcp_result_reference{path}`、`renderTextForLlm()` 产**最小**引用文案；< T → 内联原文。
- `ToolExecutionResult` 保留 `output: ToolOutput`，`result() = renderTextForLlm()`，`imageParts()` 委托 `output`。`Agent`/`PlanExecuteAgent` 只用 `result()`、零改动。**模型只见 `result()` 字符串 + image parts；`ToolOutput` 全程 host-only、不上线。**

可演示（测试）：`InMemoryTransport` 注册 `tools/call` 返回大 `structuredContent` → 断言文件已 pretty 落盘、模型可见 `result()` 有界且含路径、同 id 重放不重写；小 `structuredContent`（<T）→ 内联原文、无文件。

## Acceptance criteria

- [ ] `McpCallToolResult` 建模 `structuredContent`（JsonNode）；`toToolOutput()` 产 typed blocks；DTO 不碰 fs
- [ ] `ToolOutput` 保留 `text()` 兼容 + 新增 block 列表 + `renderTextForLlm()`（纯 flatten）
- [ ] 结构化结果 ≥T=25k → 落盘 pretty `.json`（`wx` 幂等），block→`mcp_result_reference`，`result()` 为有界最小引用（路径 + 大小 + 类型）
- [ ] <T → 内联原文、不落盘
- [ ] `ToolExecutionResult` 保留 `output`；`result()=renderTextForLlm()`；`Agent`/`PlanExecuteAgent` 零改动；`ToolOutput` 不上线
- [ ] 落盘目录 `.cafecli/tool-results/{session}/{scope}/`，`{session}` 复用 `sessionId`、`{scope}`=`main`
- [ ] 测试（seam）：`InMemoryTransport` 端到端（落盘 + 有界 + 幂等）；`McpCallToolResultTest`（structuredContent 进 DTO + blocks）

## Blocked by

None - 可立即开始
