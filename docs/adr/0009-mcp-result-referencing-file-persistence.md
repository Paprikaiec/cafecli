# MCP 大结果引用化改用文件持久化，放弃 in-memory store 与落盘脱敏

[[MCP 结果引用化]] 要把 MCP 工具的大体积/结构化结果挡在热上下文之外，同时保持可精确恢复。原设计（MCP 设计 spec item 6，Q17–Q28）是一个专用的 in-memory `McpResultStore`：原始 payload 按 `ToolRegistry` scope 常驻内存、父子任务硬隔离（对齐 [ADR 0007](0007-per-task-toolregistry-injected-services.md)）、模型可见 digest 必脱敏、slice 1 不给模型读回工具、另加单结果/单 scope 双上限与 LRU 淘汰、slice 2 再补 compaction ledger。对标实现（Claude Code）走的是另一条路：大结果确定性落盘到 session 文件，模型用通用 `read_file`(offset/limit) + `jq` 读回；无 LLM 摘要、无内存 store、无淘汰、无 ledger。

## 决策

改为**文件持久化 + 复用现有读回路**。超阈值 MCP 结果（含 `content[]` 大文本与 `structuredContent`）pretty-print 序列化落盘到**项目根内**的 `.paicli/tool-results/{session}/`，模型经现有 `read_file`(offset/limit) 读文本、`bash` `jq` 做结构化查询精确恢复。热上下文只放 schema 签名 + 有界取样 digest（首 3 样本 + 总数/omitted + **内联脱敏**）+ 文件路径。不新增 `read_mcp_result`、无 in-memory store、无 LRU/上限、无独立 compaction ledger（路径随 tool result 进对话历史，压缩后天然存活）。

两处**有意翻转**，故立此 ADR：

1. **落盘 payload 不脱敏。** 原始（可能含 secret 的）结果原样写盘并可被 `read_file` 读到，信任级别等同 agent 能读的任何项目内文件；脱敏只施加在常驻热上下文的**内联 digest**上。
2. **软隔离取代硬隔离。** 结果 payload 的跨任务隔离从 in-memory 硬分区降级为「不向子任务 surface 父任务结果路径」，是对 [ADR 0007](0007-per-task-toolregistry-injected-services.md)「默认隔离」在**结果 payload 这一项**上的自觉放松——[[已激活工具集]] 等会话相邻可变状态仍按 0007 每任务私有。

## 被否决的方案

- **原 in-memory `McpResultStore` + `read_mcp_result`。** 否决：重造 `read_file`+`jq` 已提供的 offset/limit 与结构化查询；LRU/上限纯粹是「因为在内存里」才需要；compaction 还得补 ledger；且 slice-1 形态根本不给模型读回路——比现状截断更差。
- **用 LLM 写语义摘要当 digest。** 否决：不确定、不可单测、每个大结果一次延迟/成本，且可能编造调试恰恰需要的那个字段（`requestId`/`traceId`/backend node id）。确定性 schema+取样可复现，精确值由文件兜底。
- **连落盘文件也脱敏。** 否决：模型本就能 `read_file` 任意项目内文件；脱敏派生结果却不脱敏其来源自相矛盾，还会破坏对 id/trace 等字段的 `jq` 精确恢复——而那正是调试要的。脱敏放在真正常驻的内联 digest 上。
- **为结果 payload 保留 0007 硬隔离。** 否决：文件路径天生是软边界；强行硬分区意味着放弃共享 session 结果目录、把 per-task store 又加回来——正是本 ADR 要删的机器。本地单用户 CLI 的隔离诉求是「别把父结果灌进/泄漏进子任务上下文」，路径不 surface 即可满足。

## 后果

- 删掉大量专用机器：无 store 类、无淘汰、无读回工具、无 ledger、无 recovery-only test inspector；测试直接读文件断言。
- **新增一处要长期盯的安全面**：原始 MCP payload（含 secret）落在 `.paicli/tool-results/`——必须 gitignore；内联 digest 必须脱敏敏感 key；未来若做「导出/分享 transcript」需一并处理这些文件。
- 结果目录**必须在项目根内**（`PathGuard` 把 `read_file` 限定在根内；home 目录版本读不到）；JSON pretty-print 落盘以便按行 `read_file`；`jq` 是软依赖，需缺失回退。
- 取代 MCP 设计 spec 中的 in-memory `McpResultStore` 决策（Q480/483/485/486/487，翻转 Q23/24/25/26）；保留 Q27（`structuredContent` 以 `JsonNode` 进 DTO）、Q28（`ToolOutput` 兼容演进）。
