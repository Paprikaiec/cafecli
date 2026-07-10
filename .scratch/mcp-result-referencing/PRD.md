# PRD: MCP 结果引用化（Option A：文件持久化 + 复用 read_file/jq）

Status: ready-for-agent
Date: 2026-07-01
Branch: feat/mcp-result-referencing（建议）
Decision record: ADR 0009（MCP 大结果引用化改用文件持久化，放弃 in-memory store 与落盘脱敏）
设计文档: `docs/superpowers/specs/2026-06-25-mcp-tool-retrieval-skill-injection-design.md`（《结果引用化实现设计（Option A）》一节，Q29–Q37）
相关 ADR: 0007（每任务独立 ToolRegistry / 默认隔离），本 PRD 在结果 payload 这一项自觉放松其硬隔离

## Problem Statement

MCP 工具（尤其 Chrome DevTools 这类调试型 server）会返回大体积或结构化结果：大 DOM snapshot、数百条 network request、trace insight、Lighthouse report、heap snapshot 分析等。这类结果统一以 `content` + `structuredContent` 返回，但 PaiCLI 当前的 `McpCallToolResult` 只建模 `content/isError`：

- `content[]` 文本被无上限 `\n\n` 拼接后整坨进热上下文；
- `structuredContent`（机器可读数据）被**直接丢弃**；
- 非 text/image 的 content 变成 `[此工具返回了 X，请向用户描述结果]` 占位符。

后果对使用者是双输：一方面单个大结果把[[任务]]的热上下文瞬间撑大，token 暴涨、可能触发对话压缩、把后续推理挤出预算；另一方面模型恰恰拿不到定位根因所需的机器可读精确字段（某条深层 network request 的 `requestId`/`traceId`/error code、DOM backend node id、trace frame、report file path）。

雪上加霜：`read_file` 在**不带 `offset`/`limit`** 时会 `Files.readString` 把整文件 dump 进上下文——任何大文件（不止 MCP 结果）都能凭一次裸读撑爆热上下文。

## Solution

按 ADR 0009 落地 **[[MCP 结果引用化]]（Option A）**：把大结果保留为“可检索的文件引用 + 紧凑摘要”，对标 Claude Code 的 large-tool-result 处理，但复用 PaiCLI 现有读回路、不新增专用读工具。

1. **超阈值 MCP 结果确定性落盘**到项目根内 `.paicli/tool-results/{session}/{scope}/{tool_call_id}.json|txt`（pretty-print），文件即引用。
2. **热上下文只留**：schema 签名 + 有界取样 digest（内联脱敏、标 omitted counts）+ 文件路径 + 一行读取指引。
3. **模型精确取回**用现有工具：`jq`（结构化查询，探测可用才建议）优先，`grep_code` + `read_file`(offset/limit) 兜底。**不新增 `read_mcp_result`**。
4. **全局加固 `read_file`**：裸读（unranged）超 ~256KB 直接 throw error-nudge（不 dump、不 truncate），引导改用 offset/limit 或检索——顺带使引用化无法被裸读绕过。
5. 小结果（< 阈值）仍**内联原文**、全保真；图片仍按图片附件走、不落 JSON。

净效果：热上下文小而可控，原始结构化结果无损、可由模型精确恢复。

## User Stories

1. As an LLM agent, I want 大 MCP 结果不要整坨塞进上下文、只给我摘要+路径, so that 我后续推理不被挤爆、也不无谓触发对话压缩。
2. As an LLM agent, I want 摘要里带 schema 签名 + 前 3 条样本, so that 我常常不用读文件就能判断结构、直接回答或写出精确 `jq`。
3. As an LLM agent, I want 摘要给我文件路径和具体读取命令（`jq` / `grep_code` / `read_file` offset）, so that 我知道怎么精确取回深层字段而不是整文件读。
4. As an LLM agent, I want `structuredContent` 里的机器可读字段（`requestId`/`traceId`/backend node id）可被我精确取回, so that 我能定位 checkout 页面回归这类根因。
5. As an LLM agent, I want 小结果（< 25k chars）直接内联原文, so that 常见小结果零额外跳转、信息全保真。
6. As an LLM agent, I want 同一个[[工具批次]]里多个大 MCP 结果不会合起来撑爆本回合, so that 并行调用也不超本回合预算。
7. As an LLM agent, I want 裸读一个超大文件时收到明确报错、让我改用 offset/limit 或检索, so that 我不会把整文件 dump 进来自己撑爆上下文。
8. As an LLM agent, I want 缺 `jq` 时也能用 `grep_code` + `read_file` offset 兜底取回字段, so that 恢复不依赖外部二进制是否安装。
9. As an LLM agent, I want 落盘 JSON 是 pretty-print, so that `read_file` 的按行 offset/limit 对结果文件可用。
10. As an LLM agent, I want digest 标注 omitted counts（总数与省略数）, so that 我清楚摘要不完整、需要时去读文件。
11. As a PaiCLI 用户, I want MCP 大结果落在项目内 `.paicli/tool-results/` 且已 gitignore, so that 不污染工作树、也不进版本库。
12. As a PaiCLI 用户, I want 正常退出时删掉本次 `{session}/` 结果目录, so that 每次运行干净起步、磁盘不长期堆积。
13. As a PaiCLI 用户, I want 崩溃时结果目录不自动清（整个 `.paicli/tool-results/` 一删即净）, so that 清理行为符合直觉、我能自己一把删干净。
14. As a PaiCLI 用户, I want `/clear` 清掉本会话主 scope 的结果文件（连同[[已激活工具集]]中的 active MCP tools）、但不动 MCP 连接/工具目录/长期记忆/后台任务, so that 清对话不误伤长生命周期状态。
15. As a 子任务/[[任务]], I want 我不会拿到父任务的结果文件路径, so that 父任务的大结果不灌进/泄漏进我的上下文（软隔离）。
16. As a 任务回收流程, I want 任务回收时删掉该任务 scope 的结果目录, so that 结果不跨任务生命周期堆积。
17. As a 安全审计者, I want 敏感 key（`token`/`authorization`/`cookie`/`secret`/`password`/`apiKey`/`set-cookie` 等）在**内联 digest**里被脱敏, so that 常驻热上下文不泄漏 secret。
18. As a 安全审计者, I want 明确“落盘文件是 raw（不脱敏）、但已 gitignore、信任级别等同任意项目内文件”, so that 我清楚这处安全面（含未来导出/分享 transcript 需一并处理这些文件）。
19. As an LLM agent, I want 图片结果仍按图片附件走、不落 JSON, so that 图片不被破坏、仍可被视觉识别。
20. As a 评测工程师, I want 一组 Chrome DevTools checkout-regression fixture 的 2 臂前后对照（raw-inline 基线 vs Option A）, so that 我能量化热上下文 token 降幅并验证根因字段可恢复。
21. As a 开发者, I want 持久化 + digest 渲染不塞进 DTO（`McpCallToolResult`/`ToolOutput` 保持纯）, so that DTO 不依赖 fs/session/scope、易于单测。
22. As a 开发者, I want `ToolExecutionResult` 保留 typed `ToolOutput` 但模型只见 `result()` 字符串, so that 我能做 block 级测试断言、且 `Agent`/`PlanExecuteAgent` 零改动。
23. As a 开发者, I want 落盘按 `{tool_call_id}` 命名、`wx` 幂等写, so that 压缩 replay 同一结果不重复落盘。
24. As a 开发者, I want 阈值 T=25k / B=125k 暂硬编码、后续可配置, so that 先落地跑通、再按实测调参。
25. As an LLM agent（续派后台[[任务]]）, I want 我 scope 下的结果文件在我被续派期间仍在, so that 空闲后继续干活还能读回我之前的结果。
26. As a 开发者, I want `structuredContent` 以 `JsonNode` 原样进 DTO、不在 DTO 层 stringify/截断/脱敏, so that 渲染/脱敏/阈值判断统一交给渲染层、职责清晰。

## Implementation Decisions

**新增组件（两个新 seam，均为最高纯度落点）**

- `StructuredContentRenderer`（纯、无 fs）：负责 schema 签名 + 有界取样 digest + 内联脱敏 + omitted counts。digest 形态（源自 grill 决策，精确刻画）：
  - schema 签名：紧凑 type signature（depth≤2、对象取前 10 key、数组取首元素类型），如 `[{requestId: string, url: string, status: number, timing: {...}}]`。
  - 取样 digest：数组显示总长度 + 前 3 元素；标量展开；嵌套超深折叠为 `{…}`；字符串截断 ≤120 chars；敏感 key 值脱敏；必带 omitted counts。
  - 目标 ≤ ~2k chars。大小判断基于 **compact** JSON 序列化长度。
- `McpResultPersister`（唯一碰 fs）：按 `{session}/{scope}/{tool_call_id}` 写 pretty `.json`（结构化）/ `.txt`（文本），`wx` 幂等；持有 session+scope；一次性探测 `jq` 是否可用（供读取指引裁剪）。

**DTO 保持纯**

- `McpCallToolResult`：新增 `JsonNode structuredContent`；`toToolOutput()` 产 typed blocks（text / image / structured_json），不碰 fs、不脱敏、不截断。
- `ToolOutput`：兼容演进——保留 `text()`；新增 typed block 列表 + `renderTextForLlm()`。blocks：`text` / `image` / `structured_json` / `mcp_result_reference`。`renderTextForLlm()` 只对**已变换** blocks 做纯 flatten（内容原顺序渲染 text/image；structured_json 放主文本后；reference 作 provenance 放末尾）。

**编排落点**

- `ToolRegistry`（持 session+scope）在 MCP 执行路径编排：对超阈值 block → `McpResultPersister` 落盘 + `StructuredContentRenderer` 出 digest → 将该 block 替换为 `mcp_result_reference{path, digest}`；[[工具批次]]聚合超 B 时，对本批 MCP 结果按大小降序落盘直到低于 B。
- `ToolExecutionResult`：保留 `output: ToolOutput`；`result() = renderTextForLlm()`；`imageParts()` 委托 `output`。`Agent`/`PlanExecuteAgent` 仍只用 `result()`，零改动。**模型只见 `result()` 字符串 + image parts；`ToolOutput` 全程 host-only、不上线。**

**阈值与目录**

- 单一 per-result 落盘阈值 `T = 25k chars`（<T 内联原文、≥T 落盘 + digest）；per-[[工具批次]]聚合预算 `B = 125k chars`。仅治理 MCP 结果（不含内置工具输出）。硬编码 now、配置化 later。
- 结果目录 `.paicli/tool-results/{session}/{scope}/{tool_call_id}.json|txt`；`{scope}` ∈ `main` / `task_N`；`{session}` 复用现有 `sessionId`；`.paicli/` 已在 `.gitignore`。

**read_file 全局加固**

- 对 **unranged** 读：byte-size 预读 stat，文件 > ~256KB 直接 throw error-nudge（“文件过大，请用 offset/limit 分页或 `grep_code`/`jq` 精确检索，勿整文件读取”），不 dump、不 truncate。ranged 读不变（≤2000 行、自带续读提示）。不引入 API token 计数（用 byte/char）。cap 可配置。此为**全局**改进、非结果目录特例。

**读回路（不新增工具）**

- 恢复链：`jq` 优先（read-hint 仅在探测到可用时建议）→ `grep_code` + `read_file`(offset) 兜底。读侧经 `PathGuard.resolveSafe` 天然可读根内结果目录，无需放宽 PathGuard；落盘是内部直写（非 `write_file` 工具），不过写侧 Guard。

**脱敏与隔离**

- 脱敏只施加在**内联 digest/preview**；落盘 payload raw（ADR 0009 翻转“落盘脱敏”）。
- 结果 payload 跨任务用**软隔离**：不向子任务 surface 父结果路径（ADR 0009 对 ADR 0007 硬隔离在结果 payload 这一项的自觉放松；其余会话相邻可变状态仍每任务私有）。

**生命周期**

- 新增窄口径 `ToolRegistry.clearConversationScopedState()`：清 active MCP tools + 对应 scope 结果目录；不清 MCP 连接、discovered tool catalog、长期记忆、后台任务。`/clear` 调它清主 scope；任务回收清 `task_N` scope；正常退出删整个 `{session}/`；**不做 startup GC**。

## Testing Decisions

**什么是好测试**：只断言外部行为——模型可见的 `result()` 字符串（有界、含 schema+样本+路径、敏感 key 脱敏）、落盘文件（存在、raw、pretty）、恢复（`jq`/`grep_code`+`read_file` 能取回深层字段）、`read_file` 裸读 throw、生命周期（目录被删）。不断言内部 block 结构，**除非**在 DTO/renderer 单元 seam——那里 block 就是该单元的外部输出。

**模块与 seam（尽量复用现有，prior art 附上）**

- **端到端（最高 seam）**：用现有 `InMemoryTransport` 注册 `tools/call` handler 返回大 `content` + `structuredContent`，走真实 MCP 路径，断言“热上下文有界 + 文件落盘 + 根因字段可由 `jq`/`grep_code`+`read_file` 取回”。Prior art：`McpClientTest` / `McpServerManagerTest` 已用 `InMemoryTransport` 驱动 server 响应。承载 Tier-2 与恢复行为。
- `McpCallToolResultTest`（**existing**）：`structuredContent` 进 DTO + `toToolOutput()` 产 typed blocks。
- `StructuredContentRendererTest`（**new，纯**）：schema 签名、取样 digest、字符串/深度截断、敏感 key 脱敏、omitted counts、compact-size 阈值判断。
- `McpResultPersisterTest`（**new，temp dir**）：pretty 落盘、`{tool_call_id}` 命名、`wx` 幂等、`{session}/{scope}` 目录。
- `ToolRegistryTest`（**existing**）：≥T 落盘 / <T 内联分支、[[工具批次]]聚合预算按大小降序落盘、`read_file` 裸读 >256KB throw（现已测 `read_file`，直接扩展）。编排断言保持轻量（只测分支点），端到端保证压在 `InMemoryTransport` 集成测试。
- `AgentClearHistoryTest`（**existing**）：`/clear` 删主 scope 结果目录。Prior art：已测 `/clear` 清 `conversationHistory` / 短期记忆 / pending Skill instruction buffer。
- `ChildToolRegistryTest`（**existing**）：子任务不得父任务结果路径（软隔离）。
- **Tier-2 2 臂前后对照**：Chrome DevTools checkout-regression fixture（大 snapshot、数百条 network request、大响应体、trace、Lighthouse path，根因字段刻意置于 digest 样本之外）——raw-inline 基线 vs Option A，产出热上下文 input-token 降幅 + 根因字段可恢复。

只跑相关子集 + quick profile（对齐仓库习惯）。

## Out of Scope

- **[[MCP 工具检索与按需激活]]** 与 **[[Skill 指令注入]]**：同一 spec 的其它 slice，另行推进。
- **`read_mcp_result` 工具**：默认不做；仅当 Q36 恢复率 eval 证明模型无法经通用路径可靠恢复时，才补一个**薄封装**（壳同一读回路、非 store、非新 id 空间）。
- **文件/resource 型结果的完整生命周期**：`file_reference` / `resource_link` / `embedded_resource` / `binary_reference` 仅命名为扩展点；本 PRD 只落 `text` 与 `structured_json`。
- **向 MCP server 再查询**（分页 / 流式 / 解析 `resource_link`）：另一能力（roadmap Tasks / streamed results），与“读回已落盘结果”无关。
- **把持久化推广到内置工具输出**：CC 对所有工具落盘；本 PRD 只治理 MCP 结果。`read_file` 全局加固是例外（它是通用读回路的必要防线）。
- **startup GC / 崩溃孤儿自动清理**：由用户手动删。
- **阈值配置化**：先硬编码 T/B 与 256KB cap。
- **API token 计数**：一律用 char/byte。
- **跨重启持久化 / compaction ledger**：结果文件随 session 删；路径在 transcript 内天然存活即可，无需独立 ledger。

## Further Notes

- 参考实现 Claude Code：`FileReadTool` 有 256KB(文件)/25k-token(输出) 双 throw cap（2026-03 曾试“截断代替抛错”，因抬高 mean tokens 回退——印证“抛错的 ~100 字节优于截断的 25K token”）；大 MCP 结果落 session 文件 + `inferCompactSchema` 签名 + 通用 Read/`jq` 读回；图片回退到截断、不落 JSON。本 PRD 对齐其思路，参数按 DeepSeek/GLM 大上下文放宽（T=25k/B=125k vs CC 50k/200k）。
- ADR 0009 记录 store→file 的架构翻转与两处安全/隔离翻转（落盘不脱敏、软隔离）；ADR 0007 在“结果 payload”这一项被自觉放松，其余会话相邻可变状态仍每任务私有。
- 完整决策链（Q29–Q37）见 spec《结果引用化实现设计（Option A）》一节。
- 后续 `/to-issues` 可据本 PRD 切票；建议纵向切片顺序：DTO+structuredContent → renderer → persister → ToolRegistry 编排+阈值/批次 → read_file 加固 → 生命周期/隔离 → InMemoryTransport 端到端 + Tier-2 度量。
