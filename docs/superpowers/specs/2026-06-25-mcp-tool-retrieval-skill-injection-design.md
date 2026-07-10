# 设计：MCP 工具检索与 Skill 即时指令注入

- 日期：2026-06-25
- 状态：决策已确认，待实现
- 作者：Evan Wang（与 Codex 协作）
- 分支：`feat/async-task-substrate`
- 相关文档：`CONTEXT.md`、ADR 0007 `per-task-toolregistry-injected-services`、ADR 0009 `mcp-result-referencing-file-persistence`
- 参考：MCP specification `2025-11-25`、MCP roadmap `2026-03-05`

## 背景

PaiCLI 当前 MCP 路径是 eager 全暴露：

1. `McpServerManager.start` 建立连接并 `initialize`。
2. 启动时立即 `client.listTools()`；支持 resources 的 server 还会追加 `list_resources` / `read_resource` 虚拟工具。
3. `replaceTools` 把所有 MCP descriptor 注册进 `ToolRegistry.tools`。
4. `Agent` 每次 LLM 调用前执行 `toolRegistry.getToolDefinitions()`，因此所有 MCP schema 都进入本轮模型输入。

这在 MCP server 少时简单可靠，但 server/tool 数一多会带来三个问题：schema token 固定成本高、模型选择工具时噪声高、动态 server 的工具列表变化会放大 prompt churn。

PaiCLI 当前 Skill 路径是“下一条真实用户输入前置注入”：

1. system prompt 里只放 Skill 索引。
2. 模型调用 `load_skill(name)` 后，工具把 `SKILL.md` body push 到 `SkillContextBuffer`。
3. `Agent.run()` 开始处理下一条用户输入时才 `drain()` 并前置。

这导致同一 ReAct 任务里刚调用 `load_skill` 后，下一次 LLM 迭代仍看不到 Skill 正文。Claude Code 的设计不是把 Skill 正文塞进普通 tool result，而是在 tool result 后追加一条内部 user/meta user message，让正文在同一任务的下一次模型调用中立即可见。

## 目标

- MCP server 启动、连接、`tools/list` 发现继续 eager，不引入“懒连接”或“懒发现”。
- 默认不把所有 MCP 工具完整 schema 发送给 LLM；改为检索后按需激活。
- 激活状态按 Agent / Task conversation lifetime 隔离，符合 ADR 0007。
- `tool_search` 采用“搜索即激活”：一次调用完成检索和激活。
- `load_skill` 后 Skill 正文在同一任务的下一次 LLM 调用中立即出现。
- 明确 system prompt、user input、tool definitions、tool results、MCP、Skill 的输入结构边界。
- 为后续 MCP/Skill 简历加分项留下可演进接口。

## 非目标

- 不延迟 MCP server startup，不延迟 `tools/list`。
- MVP 不做 embedding/RAG 召回工具，不做自动阈值模式。
- MVP 不实现 Anthropic `tool_reference` 等 provider 私有协议；使用 PaiCLI 现有工具 schema 机制。
- MVP 不把 Skill 设计扩成完整 marketplace、签名安装或 hooks runtime。
- 本设计不实现 MCP OAuth、sampling、Tasks；这些列为后续高价值方向。

## 已定决策

### MCP 工具检索与按需激活

采用 canonical term：**MCP 工具检索与按需激活（MCP Tool Retrieval and On-Demand Activation）**。

延迟的是“完整 schema 对 LLM 的暴露”，不是 MCP 连接、server 初始化或 `tools/list` 发现。MCP server 仍按当前启动路径连接；发现到的 descriptor 进入共享 MCP 工具目录。

默认策略：

- 默认 defer 所有 MCP 工具，包括 resources 虚拟工具。
- 核心内置工具、`tool_search` 始终可见。
- 支持显式 `alwaysLoad` 例外，给少数高频 MCP 工具常驻可见。
- 支持兼容模式 `full`，恢复旧行为：所有 MCP 工具 schema 全量暴露。
- 不做自动阈值：不会因为工具总数低于 N 就自动全暴露。

### 激活生命周期

激活状态属于当前 Agent / Task 的 conversation lifetime：

- 同一个 Agent 跨多轮用户输入保留。
- 后台 Task 空闲后被 `send_message` 续派时保留。
- 子任务不继承父 Agent 的已激活工具集。
- 兄弟任务之间不共享。
- `/clear`、新会话、任务回收后清空。

这与 ADR 0007 的“昂贵服务显式共享，会话相邻可变状态私有”一致：MCP 连接和工具目录可共享，已激活工具集不可共享。

### `tool_search` 语义

`tool_search` 是常驻可见内置工具。它检索 MCP 工具目录并立即激活结果，不另设 `activate_tool`。

MVP schema 使用简单字段，适配当前 `fromMap` 参数通道：

```text
tool_search(query: string, top_k?: integer)
```

`query` 支持两类输入：

- 自然语言：`"browser screenshot current page"`，返回并激活 Top-K。
- 精确选择：`"select:mcp__chrome-devtools__take_snapshot,mcp__filesystem__read_file"`，只激活明确命中的工具。

默认 `top_k = 5`，最大 `10`。精确选择同样受最大激活数约束，避免一次把大量工具重新暴露给模型。

结果返回紧凑摘要，不返回完整 schema：

```text
MCP tool search: 5 activated / 84 deferred

Activated:
- mcp__chrome-devtools__take_snapshot — Capture the current page accessibility snapshot.
- mcp__chrome-devtools__click — Click an element on the page.

Already active:
- mcp__chrome-devtools__navigate_page

Not found:
- mcp__foo__bar

Pending servers:
- slow_server (STARTING)

Activated tools will be callable by exact name in the next LLM call.
```

完整 schema 从下一次 `getToolDefinitions()` 起出现；这保持工具调用 API 的正常流程。

### Skill 即时指令注入

`load_skill` 命中的 `SKILL.md` body 不放进普通 tool result。普通 tool result 在模型眼里更像“事实输入 / 执行结果”；Skill body 是操作指引，应作为同一任务的内部 user message 注入。

插入点固定为：

1. assistant tool-call message
2. 所有对应 tool result messages
3. Skill instruction user message
4. 下一次 LLM 调用

这样既保留 API 对 tool-call/tool-result 配对的要求，也让 Skill 正文在同一 ReAct 任务中立即生效。

`load_skill` 的普通工具返回只保留确认信息，例如：

```text
已加载 skill 'diagnose' 的完整指引，将在下一次模型调用前作为任务指令注入。
```

Skill 正文注入格式：

```md
## 已加载 Skill：diagnose

以下内容是本任务的操作指引，请在后续步骤中遵守。

...SKILL.md body...

---
```

Skill 注入仍然是一次性 drain，不每轮重复前置。作为本 MVP 的健壮性补充，应记录 invoked Skill 名称、来源路径与内容 hash，用于 conversation compaction 后保留一个短的 loaded-Skills 摘要或重新注入压缩版提醒；这不是单独的 Skill 路线图主线。

## 模型输入结构

目标结构如下：

| 输入位置 | 放什么 | 不放什么 |
|---|---|---|
| system prompt | 基础 Agent 纪律、当前日期/时区、memory context、Skill 索引、MCP resource index 摘要、`tool_search` 使用说明 | MCP 全量工具 schema；Skill 正文 |
| user message | 用户原始输入、本地 `@path` 展开、MCP resource `@server:uri` 展开、联网预检结果、内部 Skill 指令消息 | MCP 工具 schema |
| tool definitions | 核心内置工具、`tool_search`、alwaysLoad MCP 工具、当前已激活 MCP 工具 | 未激活 MCP 工具 |
| tool result | 工具执行事实结果、错误、摘要、文件 diff、MCP call result | Skill 正文 |
| MCP 工具目录 | 全部已发现 MCP descriptor 与 invoker | 直接发送给 LLM |
| Skill 系统 | system prompt 只放索引；body 经 `load_skill` 后注入内部 user message | 未加载 Skill body |

这回答了“system prompt / user input / tool / MCP / skill 分别放在哪”的边界：

- MCP descriptor 的完整 schema 属于 tool definitions，但只有激活后才进入。
- MCP resource mention 展开后的内容属于 user message，因为它是用户显式引用的上下文。
- Skill 索引属于 system prompt，因为它是模型可发现能力目录。
- Skill 正文属于内部 user message，因为它是用户/系统授权的操作指引，不是工具事实。

## 架构设计

### MCP 工具目录

新增共享组件概念：`McpToolCatalog`。

职责：

- 保存所有 server 的 discovered tools。
- 保存 descriptor、namespaced name、server、raw tool name、description、input schema、invoker。
- 提供按 server 原子替换：`replaceServerTools(serverName, tools, invokerFactory)`。
- 提供检索：`search(query, topK)`、`select(names)`。
- 提供内部直调：给 StepSearch 代理等内部路径使用，不要求工具对 LLM 可见。

`McpServerManager.replaceTools` 不再直接把所有 MCP 工具塞进模型可见的 `tools` map，而是更新 catalog。`notifications/tools/list_changed` 继续触发 server 级替换。

### ToolRegistry 可见性

`ToolRegistry` 继续作为 Agent / Task 的工具入口，但拆出两层状态：

- 共享：`McpToolCatalog`，由父子 registry 引用同一实例。
- 私有：`ActivatedToolSet`，每个 registry 自己维护。

`getToolDefinitions()` 返回：

1. 非 MCP 内置工具。
2. `tool_search`。
3. 配置为 alwaysLoad 的 MCP 工具。
4. 当前 registry 已激活且仍存在于 catalog 的 MCP 工具。

`doExecuteTool` 对 MCP 工具增加可见性守卫：

- 如果是 alwaysLoad 或 active，正常调用 catalog invoker。
- 如果未激活，返回明确错误：`MCP 工具未激活，请先用 tool_search 检索并激活: <name>`。
- 内部直调 API 不走这个守卫，仅供内置代理使用。

这样即使模型幻觉出隐藏 MCP 工具名，也不会绕过检索激活流程。

### 子任务

`ToolRegistry.createChildRegistry()` 行为调整：

- 继续共享 MCP 连接、catalog、LSP、网络、审计、SkillRegistry。
- 新建空的 `ActivatedToolSet`。
- 新建空的 Skill instruction buffer。

子任务如果需要 MCP 工具，自己调用 `tool_search`。父任务可在 `spawn_agent` 指令里告诉子任务应该搜索哪个工具，但不直接传递激活状态。

### StepSearch MCP 代理

当前内置 `web_search` / `web_fetch` 会在 Step 模型下优先转调 `mcp__step_search__web_search` / `web_fetch`。新设计下这两个 MCP 工具默认也可以延迟暴露，但内置 web 工具仍应能使用它们。

需要把判断从 `tools.containsKey(STEP_SEARCH_TOOL)` 改成 `catalog.has(STEP_SEARCH_TOOL)`，调用从 `executeToolOutput(STEP_SEARCH_TOOL, ...)` 改成内部 direct MCP call。模型看到的仍是内置 `web_search` / `web_fetch` schema。

### 配置

MVP 配置保持最小：

- 全局曝光模式：
  - system property：`paicli.mcp.toolExposure`
  - env：`PAICLI_MCP_TOOL_EXPOSURE`
  - 值：`deferred`（默认）或 `full`
- server 级 alwaysLoad：
  ```json
  {
    "mcpServers": {
      "chrome-devtools": {
        "command": "npx",
        "args": ["-y", "chrome-devtools-mcp@latest", "--isolated=true"],
        "alwaysLoad": ["take_snapshot", "navigate_page"]
      }
    }
  }
  ```

`alwaysLoad` 支持 raw tool name 和 namespaced name。resources 虚拟工具也可以显式写入，例如 `list_resources` / `read_resource`。

### 检索排序

MVP 使用 deterministic lexical ranking：

1. exact namespaced name。
2. exact raw tool name within server。
3. server name + raw tool name token match。
4. tool name token match。
5. description token match。
6. input schema property name match。

不使用 embedding。原因：工具名/描述通常短且结构化，lexical 可解释、可单测、无需额外索引或模型成本。

### `tools/list_changed`

当 server 发送 `notifications/tools/list_changed`：

- catalog 原子替换该 server 的 entries。
- active set 保留 names，但 `getToolDefinitions()` 会过滤 catalog 中已不存在的 name。
- 如果同名工具 schema 变化，下一次 LLM 调用看到新 schema。
- `tool_search` 结果可以报告 `removed/unavailable`，但不主动往 conversation 注入事件。

这避免动态工具刷新破坏当前对话历史，同时让新一轮工具定义自然收敛。

## Skill 集成设计

### Agent ReAct

当前代码只在 `Agent.run()` 开头：

```java
String userMessageContent = prependSkillBodies(userInput);
conversationHistory.add(user(userMessageContent));
```

目标改为工具结果回灌后立即 drain：

```java
conversationHistory.add(assistant(toolCalls));

List<ToolExecutionResult> results = executeToolCalls(...);
for (ToolExecutionResult result : results) {
    conversationHistory.add(tool(result.id(), result.result()));
}
appendImageToolMessages(results);
appendPendingSkillInstructionMessage();

continue;
```

`appendPendingSkillInstructionMessage()` 只在 buffer 非空时追加 `LlmClient.Message.user(...)`。

### Plan-and-Execute

`PlanExecuteAgent` 也有 `SkillContextBuffer` 和 `prependSkillBodies`。需要同步改为同一语义：

- task input 开头不再消费 `load_skill` 产生的 body。
- 每个 executor LLM tool loop 在 tool results 后追加 Skill instruction user message。

否则 `/plan` 和 ReAct 会出现 Skill 行为分裂。

### Prompt 和文档

需要更新：

- `load_skill` 工具描述：从“下一轮 user message”改成“下一次模型调用前的内部 user message”。
- `SkillIndexFormatter` 文案同样更新。
- `SkillContextBuffer` 注释更新为“待注入 Skill 指令消息”，或重命名为 `SkillInstructionBuffer`。
- README / AGENTS / docs reference 中所有“下一轮 user message 前置注入”改成新语义。

### Skill 压缩健壮性

已加载 Skill 的正文注入后会变成 conversationHistory 中的一条内部 user message。若后续触发 conversationHistory compaction，不能只依赖摘要模型“记得”这些操作指引。MVP 应保留轻量 invoked-Skill ledger，并在压缩后保留可审计的 loaded-Skills 摘要或压缩版提醒；不新增 frontmatter 策略，也不扩展全局 Skill index。

## 兼容性与风险

### 默认 defer 可能改变旧用户体验

旧行为下模型可以直接调用任意 MCP 工具。新默认下它必须先 `tool_search`。缓解：

- `tool_search` 常驻可见，system prompt 明确说明。
- 提供 `PAICLI_MCP_TOOL_EXPOSURE=full` 兼容模式。
- 高频工具可配置 `alwaysLoad`。

### Tool descriptions 来自 MCP server，不能完全可信

MCP spec 明确工具描述/annotations 应被视为来自 server 的不可信信息。`tool_search` 结果只作为能力发现，不应把 description 当系统指令执行。

缓解：

- `tool_search` 结果不拼接大段 description。
- 不把工具 description 注入 system prompt。
- MCP 工具调用仍走现有 HITL / AuditLog / BrowserGuard / PathGuard 体系。

### Skill body 用 user message 会提高指令权重

这是有意设计：Skill body 是操作指引，不是事实结果。风险是恶意或低质量 Skill 可能影响模型行为。

缓解：

- Skill 仍需要已安装且启用。
- system prompt 索引只展示受控 SkillRegistry。
- 后续增加 skill provenance、allowed-tools、禁用模型自动调用等 frontmatter 策略。

### 多工具批次里同时调用 `load_skill` 和其他工具

Skill body 必须等所有 tool result 都回灌后再追加，不能插在 tool results 中间，否则部分 provider 会拒绝 tool-call/tool-result 配对。

这意味着同一批次的其它工具执行不会受刚加载 Skill 的正文影响；下一次 LLM 迭代会受影响。这是正确边界。

## 测试计划

### MCP

- `McpToolCatalogTest`
  - server entries 可按 server 原子替换。
  - duplicate names 保持现有校验语义。
  - list_changed 后 removed tools 不再出现在 catalog。
- `ToolRegistryMcpVisibilityTest`
  - 默认只暴露内置工具 + `tool_search`。
  - 未激活 MCP 工具不出现在 `getToolDefinitions()`。
  - `tool_search` 后 Top-K 工具进入 definitions。
  - 未激活 MCP 工具直接调用返回“请先 tool_search”。
  - `full` 模式恢复全量 MCP definitions。
- `ToolSearchToolTest`
  - natural language query 排序稳定。
  - `select:` 精确激活。
  - `top_k` 默认 5、最大 10。
  - pending/error server 在结果中提示但不失败。
- `ChildToolRegistryTest`
  - child 共享 catalog。
  - child 不继承 parent active set。
  - sibling active set 隔离。
- `StepSearchDelegationTest`
  - StepSearch MCP 工具未暴露给 LLM 时，内置 `web_search` 仍可 direct call。

### Skill

- `LoadSkillToolTest`
  - 工具返回不包含 body。
  - body 进入 pending instruction buffer。
- `AgentSkillInjectionTest`
  - LLM 第一次请求 `load_skill`。
  - tool result 后追加 user skill instruction message。
  - 第二次 LLM 调用能看到 Skill body。
  - 注入只发生一次。
- `AgentSkillToolOrderingTest`
  - 同批多个 tool call 时，Skill user message 在所有 tool result 后。
- `PlanExecuteAgentSkillInjectionTest`
  - `/plan` executor 路径与 ReAct 一致。
- `AgentClearHistoryTest`
  - `/clear` 清空 active tools 与 pending Skill instruction buffer。

## 文档联动

实现时需要同步：

- `AGENTS.md`：MCP 动态工具从“自动注册并全部可见”改成“进入目录，按需激活”；Skill 行为改成即时内部 user message。
- `README.md`：MCP 配置新增 `alwaysLoad` 与 `PAICLI_MCP_TOOL_EXPOSURE`；工具列表说明改成 `tool_search`。
- `docs/agents-reference.md`：补充 StepSearch direct MCP call 与可见性边界。
- `CONTEXT.md`：已更新 canonical terms。

暂不新建 ADR。原因：默认隔离已经由 ADR 0007 支撑；这份 spec 足以解释具体 feature 的 tradeoff。若后续把 deferred 作为长期不可配置默认且引发兼容争议，再补 ADR。

## 实施切片

1. **MCP catalog 与 visibility**
   - 新增 catalog / active set。
   - `getToolDefinitions()` 按 visibility 过滤。
   - `full` 模式保持旧行为。
2. **`tool_search`**
   - 常驻注册。
   - lexical search + `select:`。
   - search 即激活。
3. **StepSearch direct call**
   - 内置 web 工具改查 catalog，不依赖模型可见 `tools` map。
4. **子任务隔离**
   - child registry 共享 catalog，新建空 active set。
5. **Skill 即时注入**
   - ReAct tool result 后追加 Skill user message。
   - PlanExecuteAgent 同步。
   - 更新 prompt 文案。
6. **文档与测试**
   - README / AGENTS / docs reference。
   - 加单测并跑 quick profile 相关子集。

## 可增加简历含金量的后续方向

后续讨论确认：MCP Tasks 是当前 MVP 之后的主线候选，但需要等会话内 async Task substrate 开发完成后再切换分支推进；Skill 后续方向暂不定，继续评估是否存在比新增 frontmatter 策略更有差异化的能力。

### MCP 能力版图

截至 MCP specification `2025-11-25` 与 roadmap `2026-03-05`，MCP 可讨论的能力应分层看：

| 能力层 | MCP 能做什么 | PaiCLI 当前状态 / 判断 |
|---|---|---|
| Tools 暴露与调用 | server 通过 `tools/list` 暴露工具 schema，client 通过 `tools/call` 调用；支持 `tools/list_changed`、tool annotations、output schema、structured/unstructured result。 | 已有 eager 全暴露；当前 MVP 改为[[MCP 工具检索与按需激活]]。 |
| Tool result 类型 | tool result 可包含 text、image、audio、`resource_link`、embedded resource、`structuredContent`；roadmap 也明确提到 streamed/reference-based results。 | 已接受[[MCP 结果引用化]]为近期待细化方向；Chrome DevTools MCP 可直接受益。 |
| Resources | server 暴露 URI 化上下文，支持 `resources/list`、`resources/read`、templates、subscriptions 与 list/updated notifications。 | 已有 list/read、resource mention、cache/invalidation；下一步只在结果引用化中复用，不单独扩展成资源管理系统。 |
| Prompts | server 暴露 prompt templates，client 可列出并获取 prompt 内容。 | PaiCLI 目前只做 `/mcp prompts` 列表；因 Skill 已覆盖主要提示词复用场景，暂不作为主线。 |
| Roots | client 向 server 暴露 workspace roots，帮助 server 理解可访问边界。 | PaiCLI 已有项目根与 PathGuard 语义；可作为后续安全/互操作补齐项，但简历价值低于结果引用化和 Tasks。 |
| Sampling / Elicitation | server 可向 client 请求 LLM 采样或用户输入，属于 server-initiated interaction。 | 暂不优先；当前没有实际使用 Sampling / Elicitation 的 server 或产品场景。 |
| Authorization | HTTP transport 可按 OAuth 2.1 / Protected Resource Metadata / scope challenge 做企业授权。 | 企业价值强，但当前使用压力不足；适合作为后续 auth 专项，不混入本 MVP。 |
| Tasks / Agent Communication | tool 可支持 call-now / fetch-later 的 task-augmented execution；roadmap 将 lifecycle gaps 列为 Agent Communication priority。 | 最强后续主线，但等待 PaiCLI async Task substrate 完成后再切换分支。 |
| Transport / Gateway / Server Cards | roadmap 聚焦 Streamable HTTP 横向扩展、session resume/migration、server card、gateway/proxy、配置可移植。 | 平台方向大，但与当前 CLI 体验和代码基础距离较远，暂不优先。 |
| Observability / Audit | roadmap enterprise readiness 提到审计与可观测性。 | 不作为 MCP Tool Retrieval 附属项；下一期单独做统一可观测性系统。 |
| Skills over MCP / Extensions | roadmap 提到 extensions ecosystem 与 Skills primitive for composed capabilities。 | 暂不优先；等本地 Skill 与 MCP 结果/工具边界稳定后再评估。 |

按优先级推荐：

1. **MCP Tool Retrieval / Activation with measurable token savings**
   - 产出指标：schema input token 降幅、tool_search 命中率、激活后调用成功率、延迟变化。
   - 简历表达：built MCP tool retrieval layer for agent runtime, reducing prompt schema cost while preserving per-task tool isolation.
2. **MCP Tasks primitive 对接 PaiCLI async task substrate**
   - MCP roadmap 已把 Tasks lifecycle gaps 列为 Agent Communication priority。
   - PaiCLI 已有 session async Task substrate，适合实现 MCP task-augmented `tools/call`、`tasks/get`、`tasks/result`、cancel、status push。
   - 简历表达：implemented MCP agent-to-agent task protocol on top of virtual-thread task runtime.
3. **MCP Sampling + Elicitation**
   - 支持 MCP server 向 client 请求 LLM sampling 或用户补充输入。
   - 关键难点：递归调用预算、HITL 审批、权限边界、trace。
   - 暂不优先：当前没有实际使用 Sampling / Elicitation 的 MCP server 或产品场景。
   - 简历表达：added safe server-initiated LLM/user interaction loop with budget and approval controls.
4. **MCP OAuth / Enterprise auth**
   - 对齐 MCP 2025-11-25 Authorization：Protected Resource Metadata、OAuth server discovery、PKCE、token store、scope challenge。
   - 简历表达：implemented OAuth 2.1 authorization flow for MCP clients with enterprise-ready credential handling.
5. **MCP observability / audit**
   - 全链路 trace：initialize、tools/list、tool call、approval、policy deny、task status、token/cost。
   - 可导出 NDJSON 或 OpenTelemetry-style spans。
   - 暂不优先作为 MCP Tool Retrieval 的附属项；后续单独做统一可观测性系统，覆盖各类工具调用耗时、token 监控与成本/错误统计。
   - 简历表达：built audit and observability pipeline for MCP agent tool execution.
6. **MCP result / resource handling（已接受为近期待细化方向）**
   - **[Q30 更新] 以下 Q17–Q28 中所有基于 in-memory `McpResultStore` 的部分，已被文末《结果引用化实现设计（Option A）》取代或翻转；此处保留仅作 grill 历史。**
   - 支持 structured content、resource-like tool results、大结果引用化与按需读取，避免把大段 MCP 输出直接塞进 hot context。
   - 复用现有 MCP resource cache / mention 概念，优先解决当前结果转换只保留 text/image、其它 content type 被占位的问题。
   - Chrome DevTools MCP 1.4.0 调研确认可直接受益：其响应统一返回 `content` + `structuredContent`，而 PaiCLI 当前 `McpCallToolResult` 只建模 `content/isError`，会丢失 structured content；`take_snapshot`、network/console 列表、trace insight、Lighthouse、heap snapshot 分析等都在 structured content 中保留机器可读数据。
   - Chrome DevTools MCP 的首个实现切片不应只盯 MCP `resource_link`：该 server 目前更常见的是大文本 + structured content + saved/temp file path。应先保留 structured content，并把 `snapshotFilePath`、Lighthouse reports、trace files、network body files、large screenshot temp files 等路径型结果转为可审计、可按需读取的引用。
   - 已确认 MVP 顺序：第一切片先做 `structuredContent` 保留与 size-gated rendering，不先新增 `read_mcp_result` 之类的按需读取工具；小型 structured JSON 可直接作为普通工具结果内联给模型，大型 payload 则渲染为确定性 bounded digest（top-level keys、集合规模、少量标量样本，并受字符/token 上限约束）；原始 structured data 保留在内部元数据中，第二切片再处理文件型结果、resource-like result 与可检索引用生命周期。
   - `McpResultStore` 的优势实验不应只看模型最终回答：在第一切片没有按需读取工具时，store 的直接价值是“热上下文不膨胀但原始结构化结果仍可精确恢复”。需要用 Chrome DevTools 风格 fixture 设计四组对照：现状 text/image-only（structured data 丢失）、raw-inline（答案信息完整但后续上下文暴涨）、digest-only（上下文受控但关键细节不可恢复）、digest+store（上下文受控且原始 payload 可由内部探针精确恢复）。
   - 推荐复杂场景：模拟一次 checkout 页面回归排查，fixture MCP tools 依次返回 `take_snapshot` 的大 DOM snapshot、`list_network_requests` 的数百条请求、`get_network_request` 的大响应体、trace insight、Lighthouse report path。关键根因字段刻意放在 digest 样本之外，例如某条深层 network request 的 `requestId` / `traceId` / error code、DOM node backend id、trace frame 与 report file path。验收指标包括：后续 LLM 请求 input tokens、是否触发 conversation compaction、关键字段能否从 store 按 call id/result id 精确取回、`/clear` 后是否清空、子任务是否无法读取父任务结果、超限 payload 是否按策略淘汰。
   - 已确认：第一切片可加入 test-only / package-private 的 `McpResultStore` inspector，专用于上述实验验证精确恢复、隔离、清理与淘汰行为；它不是 LLM tool，也不是用户可见 slash command。
   - 已确认：`McpResultStore` 的结果身份从现有 tool call 边界派生，不复用 MCP resource URI 语义。内部 result id 形如 `mcp_result://{agent_scope}/{tool_call_id}`；元数据至少包含 agent scope、tool call id、server、tool、arguments hash、created_at、content type、estimated size 与 eviction status。
   - 已确认：第一切片 retention policy 保持 memory-only 且双重上限。只有超过 inline threshold 的 raw structured payload 或包含文件/path-like metadata 的结果进入 store；单结果建议上限 1-2 MB serialized JSON，单 Agent / Task scope 建议总上限 8-16 MB；同一 scope 内 oldest-first 淘汰；`/clear` 清空主 scope，任务回收清空任务 scope；被淘汰 result id 只在 test inspector 中显示为 `evicted`，不可读取。
   - 已确认：第一切片的模型可见 digest 可以包含 `mcp_result://...` id，但只作为 provenance / audit 信息，不暗示可调用读取能力；文案应明确 “No read tool is available in this slice.”，避免模型臆造 `read_mcp_result` 调用。
   - 已确认：第一切片不做 special conversation-compaction handling；`mcp_result://...` 在当前上下文中只作为 provenance 出现。等第二切片引入模型可用的按需读取能力后，再设计 compaction-persistent result ledger / result index。
   - 已确认：Question 18 采用 internal typed block array，而不是把 provider-facing tool message 直接改成 JSON array。`ToolOutput` 保留 legacy `text` 路径，同时新增内部 block 列表与 `renderTextForLlm()` / 等价方法，把 typed blocks 展平成 GLM / DeepSeek / Step / Kimi 都安全的 string tool result；MCP 结构化结果、图片、provenance 与后续 file/resource reference 都挂在 typed block / metadata 上，避免让 `Agent` / `PlanExecuteAgent` 直接理解 MCP 内部协议。
   - 已确认：Question 19 第一切片只实现 `text`、`image`、`structured_json`、`mcp_result_reference` 四类 typed block；`file_reference`、`resource_link`、`embedded_resource`、`binary_reference` 只先命名为第二切片扩展点，不在当前切片完整启用，避免把文件/resource 生命周期、权限、PathGuard 与读取工具设计提前混入。
   - 已确认：Question 20 的 `renderTextForLlm()` 展平顺序保留 MCP `content[]` 原始顺序来渲染 text / image placeholder；当 MCP 同时返回 `content` 与 `structuredContent` 时，将 `structured_json` 放在主文本之后；`mcp_result_reference` 放在结构化内容之后，仅作 provenance。展平文本使用稳定标题（如 `MCP tool result`、`Structured content`、`Stored result provenance`）；小 JSON 内联，大 JSON 渲染 bounded digest + provenance id，避免把 `mcp_result://...` 放在开头诱导模型关注 plumbing。
   - 已确认：Question 21 第一切片用保守字符阈值，不引入 API token counting。`structured_json` 的 compact serialized JSON <= 8,000 chars 时直接内联；超过 8,000 chars 时渲染 bounded digest，digest 目标 <= 4,000 chars；阈值暂硬编码，后续再配置化；大小判断基于 compact JSON 而不是 pretty JSON。
   - 已确认：Question 22 的 bounded digest 使用确定性 structural digest，而不是伪装成完整 JSON。object 显示 top-level keys 与短 scalar value；array 显示总长度与前 3 个元素样本；递归深度最多 3；长字符串截断到 200 chars；大 object 按原始字段顺序取前 20 个 key 并标注 `... N more keys`；敏感 key（`token`、`authorization`、`cookie`、`secret`、`password`、`apiKey`、`set-cookie` 等）值统一 redacted；输出必须包含 omitted counts，让模型明确 digest 不完整。
   - 已确认：Question 23 中 `McpResultStore` 第一切片存 raw unredacted `JsonNode`，但默认不可见、不可日志化、不可导出。digest renderer 必须始终 redacted；test inspector 默认返回 redacted view；test-only exact lookup 只能通过 JSON pointer / hash 断言 raw 存在，不打印 secret；第一切片不做磁盘持久化，不把 raw payload 写入 memory / save / trace；第二切片若增加 `read_mcp_result`，必须在模型可见输出前再次经过 redaction / policy。
   - 已确认：Question 24 中 redaction 放在共享 `StructuredContentRenderer` / 等价 helper，而不是放进 `McpResultStore`。`McpResultStore` 只负责 raw retention、id、cap 与 eviction；`StructuredContentRenderer` 负责 compact JSON serialization、inline/digest 阈值、digest 与 redaction；`ToolOutput.renderTextForLlm()` 渲染 `structured_json` 时调用 renderer；test inspector 默认也调用 renderer，raw 断言只走 hash / JSON pointer。
   - 已确认：Question 25 中 `McpResultStore` 由 `ToolRegistry` 持有，且每个 registry scope 一份，不从父 registry 共享给子任务。主 `ToolRegistry` 持有 main-scope store；`ToolRegistry(ToolRegistry parent)` 创建新的空 child store，只共享 MCP 连接 / 工具描述等昂贵协作者；结果 payload 不跨 Agent / Task 边界。`ForegroundAgentSpawner` 可在 `createChildRegistry()` 后把 child store scope label 设为 `task_N`。
   - 已确认：Question 26 中 `/clear` 不应被描述成清理所有 session state。当前 `Agent.clearHistory()` 已清空主要对话态：`conversationHistory`、short-term memory 与 pending Skill instruction buffer；长期记忆、MCP 连接、MCP 工具目录、模型/上下文配置、audit/snapshot/browser/search 共享服务和后台任务不应被 `/clear` 清空。为未来 ToolRegistry 持有的对话生命周期状态，新增窄口径 hook：`ToolRegistry.clearConversationScopedState()`（或等价命名），首批只清空 active MCP tools 与 main-scope `McpResultStore`；不清空 MCP server 连接、discovered tool catalog 或其它长生命周期服务。
   - 已确认：Question 27 中 `structuredContent` 应先在 MCP 协议 DTO 层原样保留：`McpCallToolResult` 增加 `JsonNode structuredContent`，`toToolOutput()` 再把它转换成内部 `structured_json` typed block。不要在 MCP DTO 中 stringify、截断或 redaction；模型可见渲染、redaction、inline threshold、bounded digest 与 `mcp_result://...` provenance 都交给 `ToolOutput.renderTextForLlm()` / `StructuredContentRenderer` 处理。
   - 已确认：Question 28 中 `ToolOutput` 采用兼容式演进：保留 `text()` 作为 legacy plain-text 字段，新增 internal typed block list 与显式 `renderTextForLlm()`。`ToolOutput.text("...")` 同时填充 legacy text 与单个 `text` block；MCP 结果可产生 `text`、`image`、`structured_json`、`mcp_result_reference` blocks。`Agent`、`PlanExecuteAgent`、`McpClient.callTool()`、`ToolRegistry.executeTool()` 等模型可见/工具返回路径使用 `renderTextForLlm()`；StepSearch 可用性判断、browser post-check 等确实只需要 plain text 的窄路径可继续读 `text()`，除非后续需要结构化字段。
   - 简历表达：built reference-based MCP result handling to keep large external tool outputs retrievable without bloating agent context.
7. **Skill 候选增强（待重评估）**
   - 暂不优先新增 `allowed-tools`、`model`、`effort`、`context=fork|main`、`disable-model-invocation` 等 frontmatter 策略；这些参数的差异化收益尚不清晰，且可能增加模型上下文负担。
   - 暂不优先做 path-specific Skill activation；当前项目没有明确的 path-specific Skill 使用场景。
   - 保留 Skill compaction persistence 作为当前 Skill 即时注入 MVP 的健壮性补充：记录 invoked Skill 并在 conversationHistory 压缩后保留短摘要或压缩版提醒。
8. **Skills over MCP / provenance**
   - 将 Skill 作为可安装、可签名、可审计的 composed capability。
   - 对齐 MCP roadmap “Skills primitive for composed capabilities” 方向。
   - 简历表达：prototyped signed skill distribution over MCP with provenance and activation controls.

## 结果引用化实现设计（Option A：文件持久化 + 复用 read_file/jq）

本节取代 item 6 中基于 in-memory `McpResultStore` 的设计。slice 1 与 slice 2 合并为一次实现。

**Q29 决策**：任何超过阈值的 MCP 结果都做引用化，涵盖 `content[]` 大文本与 `structuredContent`，不再只针对 `structuredContent`。（`McpCallToolResult.toToolOutput()` 当前把 `content[]` 文本无上限 `\n\n` 拼接、非 text/image 直接占位，是必须补的洞。）

**Q30 决策**：结果引用化改为**确定性文件持久化 + 复用现有读回路**（对标 Claude Code 的 large-tool-result 处理，无 LLM 摘要、无 in-memory store、无淘汰、无独立 ledger；决策记录见 [ADR 0009](../../adr/0009-mcp-result-referencing-file-persistence.md)）。要点：

- **完整内容落点**：超阈值结果确定性序列化落盘到**项目根内**的结果目录（如 `.paicli/tool-results/{session}/`，需 gitignore；确切位置见开放项）。落项目根内是硬约束——`read_file` 经 `PathGuard.resolveSafe` 限定在根内，只有根内文件模型才读得到（已核对；PaiCLI 已有项目本地 `.paicli/` 形式）。
- **结果目录布局（Q33 决策）**：`.paicli/tool-results/{session}/{scope}/{tool_call_id}.json|txt`；`{scope}` ∈ `main` / `task_N`（按 scope 分目录便于 /clear 与任务回收整目录清理，非隔离手段）；`{session}` 复用现有 session id、无则进程启动时 mint。`.paicli/` 已在 .gitignore；`read_file` 经 `PathGuard.resolveSafe` 天然可读根内该目录，无需放宽 PathGuard。
- **落盘格式**：JSON 用 pretty-print（indent 2），让 `read_file` 的按行 offset/limit 可用；`jq` 对紧凑/pretty 都可查（对齐 CC）。文件名由 tool_call 边界派生，写入幂等（`wx` 语义），压缩 replay 不重写。
- **读回路（Q34 决策）**：不新增 `read_mcp_result`。恢复链：`jq` 优先（结构化查询，read-hint 仅在探测到 `jq` 可用时建议）→ `grep_code` + `read_file`(offset) 兜底（始终可用，即 CC 的 "search for specific content"）。持久化是内部直写（非 `write_file` 工具），不过 PathGuard 写侧；读侧走 `read_file` 天然允许。
- **read_file 全局加固（Q34 决策）**：对标 CC Read 的 256KB/25k-token 双 throw cap——PaiCLI 只做 byte-size 预读 stat（不引入 token 计数）：**unranged** 读文件 > ~256KB 直接 **throw error-nudge**（"文件过大，请用 offset/limit 分页或 `grep_code`/`jq` 精确检索，勿整文件读取"），不 dump、不 truncate（CC 2026-03 truncate 实验因抬高 mean tokens 已回退）。ranged 读不变（≤2000 行、自带续读提示）。cap 可配置。此为**全局**改进（利好一切大文件读取）、非结果目录特例；顺带使引用化无法被裸读绕过。
- **热上下文摘要**（**Q31-b 决策**）：tool result 只放 紧凑 schema 签名 + 有界取样 digest（首 3 样本、标量展开、嵌套超深折叠 `{…}`、字符串 ≤120、总数+omitted、**内联脱敏**；目标 ≤ ~2k chars）+ 文件路径 + 一行读取指引。落盘文件本身不脱敏，精确值由 `read_file`/`jq` 恢复。不再放 in-memory `mcp_result://` id；引用就是真实路径。
- **阈值（Q32 决策）**：单一 per-result 落盘阈值 `T = 25k chars`——`<T` 内联原文、`>=T` 落盘 + digest；per-[[工具批次]] 聚合预算 `B = 125k chars`，超预算时对本批 MCP 结果按大小降序落盘直到低于 `B`。理由：目标模型（DeepSeek/GLM）上下文窗口充裕，可比 CC(50k/200k) 更宽地内联、只引用真正巨大的结果。仅治理 MCP 结果（不含内置工具输出）；暂硬编码、后续配置化。
- **压缩持久化**：文件路径随 tool result 进入对话历史，压缩后指针天然存活，无需 slice-2 ledger。
- **子任务隔离**：软隔离——不向子任务 surface 父任务结果路径；不再做 in-memory 硬分区。
- **脱敏**：落盘 payload **不脱敏**（信任级别等同模型可 `read_file` 的任何项目内文件）；仅对**内联 digest/preview** 保留可选轻量脱敏，避免把 secret 常驻热上下文。
- **图片/二进制**：不落 JSON，沿用现有 image part 语义（对齐 CC「图片回退到截断、不持久化为 JSON」）。
- **生命周期与清理（Q35 决策）**：in-session——`/clear` 删 `{session}/main/`、任务回收删 `{session}/task_N/`（`ToolRegistry.clearConversationScopedState()` 除清 active MCP tools 外清对应 scope 目录，即时、按 scope）。正常退出——删整个 `{session}/` 目录（每次运行干净起步）。崩溃/非正常退出——**不做 startup GC**，孤儿目录留待用户手动删（整个 `.paicli/tool-results/` 一删即净，符合直觉）。无 per-session 大小上限。
- **验收/测试（Q36 决策）**：Tier 1 确定性测试为门禁——落盘边界（≥T 落盘 / <T 内联）、digest 有界且**仅内联脱敏**（文件 raw）、批次预算、`read_file` 裸读 throw、`jq`+`grep_code`/offset 恢复深层字段、生命周期（/clear、任务回收、退出清 session、幂等写、子任务软隔离）。Tier 2 保留 Chrome DevTools checkout-regression fixture 的 **2 臂前后对照**（raw-inline 基线 vs Option A），产出热上下文 token 降幅指标 + 根因字段可恢复。
- **执行路径落点（Q37 决策）**：DTO 保持纯——`McpCallToolResult.toToolOutput()` 只产 typed blocks（含 raw `structured_json`），不碰 fs。`ToolRegistry`（持 session+scope）编排：对超阈值 block 调注入的 `McpResultPersister`（唯一碰 fs：按 `{tool_call_id}` 写 pretty `.json`/`.txt`、`wx` 幂等、jq-probe）落盘，调 `StructuredContentRenderer`（纯：schema 签名 + 取样 digest + 内联脱敏）出 digest，将该 block 替换为 `mcp_result_reference{path,digest}`；`renderTextForLlm()` 只对已变换 blocks 做纯 flatten。`ToolExecutionResult` **保留 typed `ToolOutput`**（`output`），`result()`=`renderTextForLlm()`、`imageParts()` 委托 `output`；`Agent`/`PlanExecuteAgent` 只用 `result()`、零改动（原 Q29：保留）。**大模型只见 `result()` 字符串 + image parts；`ToolOutput` 全程 host-only、不上线。**

**被取代 / 翻转**：取代 Q480、Q483、Q485、Q486、Q487；翻转 Q23/Q24（store 侧脱敏）、Q25（硬隔离）、Q26（清 in-memory store → 清结果目录）。**保留** Q27（`structuredContent` 以 `JsonNode` 进 DTO）、Q28（`ToolOutput` 兼容演进 + `renderTextForLlm()`）、typed block 作为**落盘/渲染前的内部表示**。**重构** Q21（阈值→落盘阈值）、Q22（digest 形态→ Q31）、Q19（`mcp_result_reference` block →「持久化文件引用」）。

**开放项**：无——Q29–Q37 全部落定。读回路复用 `read_file`/`jq`/`grep_code`，**不新增 `read_mcp_result`**（见下）。

## 验收标准

- 接入多个 MCP server 时，默认 LLM tool definitions 不再包含全部 `mcp__` 工具。
- 模型可通过 `tool_search` 查到并激活 MCP 工具，下一次 LLM 调用可按 schema 正常调用。
- 父 Agent 激活的 MCP 工具不会出现在新子任务 definitions 中。
- `load_skill` 后，同一 ReAct 任务的下一次 LLM 调用能看到 Skill body。
- Skill body 不出现在普通 tool result 中。
- StepSearch 代理在 deferred 模式下仍能工作。
- `/clear` 清空 pending Skill instruction、active MCP tools 与本 session 的结果引用目录（`.paicli/tool-results/{session}`），但不清空 MCP 连接、discovered tool catalog、长期记忆或后台任务。
