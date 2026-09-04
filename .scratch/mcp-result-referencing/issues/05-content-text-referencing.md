# 05 — content[] 大文本引用化

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/mcp-result-referencing/PRD.md`（ADR 0009）

## What to build

把引用化从“只治 `structuredContent`”扩到 `content[]` 的大文本，完成 Q29「任何超阈值 MCP 结果都引用化」的文本分支。当前 `toToolOutput()` 把 `content[]` 文本无上限 `\n\n` 拼接进热上下文，是必须补的洞。

- `content[]` 文本拼接后若 compact 长度 ≥ **T=25k chars** → 作为 `text` block 经 `McpResultPersister` 落盘 `.txt`；digest = head/tail 截断 + 路径 + 读取指引；< T → 内联。
- 文本只能 head/tail 摘要（不像 JSON 能做结构 digest）；复用 02 的 persister/编排与 04 的读取指引。
- 图片仍按图片附件走、不落盘（沿用现有 image part 语义）。

可演示（测试）：`InMemoryTransport` 返回大文本 `content`（无 `structuredContent`）→ 断言落 `.txt`、模型可见 head/tail + 路径、可经 `read_file`(offset)/`grep_code` 取回中段文本。

## Acceptance criteria

- [ ] `content[]` 大文本（≥T）落 `.txt` + head/tail digest + 路径；<T 内联
- [ ] 复用 02 的 persister/编排、04 的读取指引
- [ ] `content[]` 不再无上限拼接进热上下文（Q29 文本分支完成）
- [ ] 图片不落盘、沿用 image part
- [ ] 测试（seam）：`InMemoryTransport` 大文本端到端（落盘 + head/tail + 可恢复）

## Blocked by

Issue 02（引用化 tracer bullet）；读取指引复用 Issue 04
