# AGENTS.md

仓库给 Agent / 新线程使用的首读入口。详细行为描述见 `docs/agents-reference.md`。

## Language Policy

- Think, reason, and write ALL output in English by default, no matter what language the user writes in. 
- This applies to: all internal reasoning, session chat, and developer-facing technical docs (design docs, architecture notes).
- If the user explicitly requests another language in a specific message (e.g. "用中文回答"), comply for that one response, then return to English.

## 信息优先级

1. 代码实际行为 > 2. `AGENTS.md` > 3. `README.md` > 4. `ROADMAP.md` > 5. `CLAUDE.md`

`ROADMAP.md` 代表演进方向，不代表已交付。

## 项目快照

- 项目名：`PaiCLI`
- 定位：面向商业使用的 Java Agent CLI 产品，对标 Claude Code
- 已交付 21 期（ReAct → Plan+DAG → Memory → RAG → Multi-Agent → HITL → 并行工具 → 多模型 → 联网 → MCP 核心 → MCP 高级 → 长上下文 → Chrome DevTools → CDP 会话复用 → Skill → TUI → LSP 诊断 → Side-Git 快照 → Prompt 分层 → Runtime API → 图片输入）
- 进行中：会话内异步任务（Task）substrate —— 主 Agent 用 `spawn_agent`/`send_message`/`task_output` 自主派生子任务。Wave 1（前台/并行派生 + 每任务隔离 + 续派/检视 + prompt 纪律 + `/team`/`AgentOrchestrator` 退役）已落地；Wave 2（事件循环 + push + Java 21 虚拟线程 + 准入 + `DurableTaskManager` 退役）待做（见 ADR 0004-0007 + `.scratch/async-task-substrate/PRD.md`）。OAuth / sampling / recovery 作为后续 MCP 增强
- Banner 版本：`v16.1.0`，Maven 产物：`paicli-1.0-SNAPSHOT.jar`（两者不一致是正常状态）

## 运行前提

- Java 21+ / Maven（虚拟线程：会话内异步任务跑在虚拟线程上，运行环境下限为 JRE 21）
- 可选：`ripgrep`（`grep_code` 会优先使用；未安装时自动回退 Java 扫描）
- 至少一个 API Key：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY`

## 常用命令

```bash
cp .env.example .env
mvn clean package        # 默认跳过测试，优先产出可手工验收 jar
java -jar target/paicli-1.0-SNAPSHOT.jar
mvn test -Pquick          # 常规回归
mvn test -Pphase16-smoke  # TUI 相关
mvn test -Dtest=XxxTest -DskipTests=false   # 针对性
mvn test -DskipTests=false                  # 全量回归
java -jar target/paicli-1.0-SNAPSHOT.jar run --file issue.md --trace-file trace.jsonl  # 无人值守单任务 + NDJSON trace
```

## 架构概览

两条主执行路径，共享 ToolRegistry / MemoryManager / SnapshotService：

| 路径 | 入口 | 触发 |
|------|------|------|
| ReAct（含自主异步派生） | `Agent.java` + `ForegroundAgentSpawner.java` | 默认模式 |
| Plan-and-Execute | `PlanExecuteAgent.java` | `/plan` |

> 确定性多 Agent 编排（`AgentOrchestrator` / `/team`）已退役（ADR 0004），由「主 Agent 自主异步派生子任务」取代：主 Agent 在默认 ReAct 模式下用 `spawn_agent` / `send_message` / `task_output` 工具自主派生、续派、检视子任务（会话内异步任务 substrate，见 `.scratch/async-task-substrate/PRD.md`）。无人值守 / SWE-bench 走 `paicli run --strategy=react`（默认即 react），还原 agent 自身能力、不启用确定性编排脚手架。

核心内置工具 12 个：`read_file` / `write_file` / `edit_file` / `list_dir` / `glob_files` / `grep_code` / `execute_command` / `create_project` / `search_code` / `web_search` / `web_fetch` / `revert_turn`

会话内异步任务工具 3 个：`spawn_agent`（前台/并行派生子任务）/ `send_message`（续派空闲任务）/ `task_output`（检视任务完整轨迹）—— 主 Agent 自主多 Agent 编排的唯一模型。

代码库理解默认走 Claude Code 式实时探索：`glob_files` 找候选文件、`grep_code` 精确定位符号或字符串、`read_file` 按需读取具体行段。`read_file` 裸读大文件默认超过 256KB 会直接报错并提示改用 `offset`/`limit`、`grep_code` 或 `jq` 精确检索；阈值可用 `paicli.read_file.max_unranged_bytes` / `PAICLI_READ_FILE_MAX_UNRANGED_BYTES` 调整。`grep_code` 优先使用本机 `ripgrep`，不可用时回退到 Java 扫描；结果受 `max_results` / `head_limit` / `max_chars` 预算约束，返回 `partial: true` 或 `suggested_reads` 时应继续缩小搜索范围或按建议读取行段。`search_code` 是 RAG 语义辅助，适合模糊自然语言、关键词不明确、常规搜索无果、巨型/跨知识检索场景，不作为精确代码定位的首选。

MCP 动态工具：`mcp__{server}__{tool}`（+ resources 虚拟工具）

MCP `structuredContent` 会保留为 typed tool output；单个结构化 MCP 结果 compact JSON ≥25k chars 时，`ToolRegistry` 会把 raw pretty JSON 持久化到 `.paicli/tool-results/{session}/{scope}/{tool_call_id}.json`，热上下文只回传 schema 签名、前 3 条样本/omitted counts、文件路径与读取提示。MCP 大文本结果 ≥25k chars 会落 `.txt`，热上下文只回传 head/tail digest + 路径。工具批次内 MCP inline payload 合计超过 125k chars 时，会按大小降序继续落盘，直到批次回到预算内。读取提示会在探测到 `jq` 可用时建议 `jq`，否则只建议 `grep_code` + `read_file` offset/limit；`grep_code` 默认仍排除 `.paicli`，但显式把 `path` 指到 `.paicli/tool-results/...` 时允许检索结果文件。内联 digest 会脱敏 token / authorization / cookie / secret / password / apiKey / set-cookie 等敏感 key；落盘文件保持 raw。结果 scope 默认 `main`，子任务使用 `task_N` 并共享同一 session id；`/clear` 清 main scope 结果与当前 active MCP tools，任务回收清对应 task scope，正常退出清整个 session 目录，不做 startup GC。小结构化/文本结果继续内联；图片结果仍走 imageParts，不落 JSON。

MCP 配置会合并用户级 `~/.paicli/mcp.json` 与项目级 `.paicli/mcp.json`；`${VAR}` 支持系统环境变量、系统属性、项目 `.env`、用户 `~/.env`。检测到 `STEP_API_KEY` 时会自动内置 `step_search` 远程 MCP（显式同名配置优先）。

DeepSeek V4 / Kimi thinking 模式下，assistant tool-call 消息的 `reasoning_content` 必须随下一轮请求历史带回；其他 provider 默认只把 reasoning 写日志 / 展示。

## 仓库结构

```
src/main/java/com/paicli/
├── agent/       Agent.java, PlanExecuteAgent.java, ForegroundAgentSpawner.java, task/ (Task, TaskRegistry, AgentSpawner)
├── cli/         Main.java, CliCommandParser.java, PlanReviewInputParser.java
├── browser/     BrowserSession, BrowserGuard, SensitivePagePolicy
├── llm/         GLMClient, DeepSeekClient, StepClient, KimiClient, FreeLlmApiClient
├── context/     ContextProfile, ContextMode, TokenUsageFormatter
├── memory/      MemoryManager, ConversationHistoryCompactor, LongTermMemory
├── plan/        Planner, ExecutionPlan, Task
├── rag/         CodeIndex, CodeRetriever, VectorStore, CodeChunker
├── lsp/         LspManager, LspDiagnosticFormatter
├── prompt/      PromptAssembler, PromptContext, PromptRepository
├── image/       ImageReferenceParser
├── runtime/     api/ (RuntimeApiServer + RuntimeThreadStore) + task/ (TaskRunner) + CancellationRegistry/Token
├── snapshot/    SideGitManager, SnapshotService
├── tool/        ToolRegistry
├── mcp/         McpClient, McpServerManager, transport/, resources/, mention/
├── hitl/        HitlToolRegistry, ApprovalPolicy, TerminalHitlHandler
├── web/         SearchProvider, WebFetcher, HtmlExtractor, NetworkPolicy
├── policy/      PathGuard, CommandGuard, AuditLog
├── skill/       SkillRegistry, SkillContextBuffer, SkillIndexFormatter
└── render/      Renderer, InlineRenderer, PlainRenderer, RendererFactory
```

启动与 inline 渲染当前约定：

- 开屏 Banner 使用无右边框的简洁布局，避免 CJK/ANSI 字宽导致右侧竖线错位；Phase 22 后默认是 π 主题彩色 logo + Qoder 风格首屏，只展示模型、MCP、Skill、ReAct 状态和三条 getting-started tips，不再把 MCP server 明细刷成启动日志。
- inline 模式使用 JLine 4 的 LineReader 编辑能力，默认提示符是 `* `，右提示显示 `message / @path / @image`。
- 默认 CLI 启动路径应先 `Renderer.start()` 并初始化底部 dock；inline 首屏不要在 `readLine` 前裸写 stdout，而是通过 `InlineRenderer.installStartupScreen(...)` 挂到 `LineReader.CALLBACK_INIT`，首次进入输入时用 `printAbove` 一次性显示完整 Banner + tips，避免 logo 被 LineReader 首次重绘滚出可视区域。
- `BottomStatusBar` 现在是 JLine `Status` 托管的底部 dock：由 JLine 维护滚动区域和状态行位置，不再手写 `\n` / `moveUp` / `CLEAR_TO_EOS` 清屏。输入期会把 LineReader 光标定位到 dock 上方一行，让 `*` 输入行和 Status 同处底部区域；dock 保留两类信息：上层模式 + MCP/Skill 摘要，下层 Auto Model / model / phase / ctx 百分比与 token / cost / elapsed / cwd。`ctx` 表示当前仍会带入下一轮请求的上下文估算；`in/out/cache` 表示最近任务的 LLM 调用统计，二者不要混用。
- 普通任务和斜杠命令提交后，`Main` 会把本轮原始输入以暗色整行块写回 transcript：输入态左提示仍是 `* `，提交回显左提示改为 `>`；单行输入只占一行，不额外追加空白行。普通任务随后再展开 MCP resource / 本地 `@path` 并进入 Agent；不要只依赖 JLine 提交行残留，否则 activity 重绘或 dock 刷新可能让用户输入从可见历史里消失。`/clear` 清空 conversationHistory、shortTermMemory、待注入 Skill buffer，并重建不含上一轮检索记忆的 system prompt；长期记忆保留。
- ReAct LLM 调用期间，inline renderer 使用固定高度 live thinking 区动态显示 `Thinking...` 和灰色竖线 reasoning 预览；该区域只能清理自己刚打印的几行，不能用独立 JLine `Display.update()` / `CLEAR_TO_EOS` 向上覆盖 transcript。content 或 tool call 开始前先清掉 live 区，再把完整 reasoning 引用块落到正文区，正文回答用低调标记起始，不再刷强标题。
- 交互期输出应优先走 `Renderer.stream()`；`Main`、`PlanExecuteAgent`、`Planner` 都支持把输出流接到 inline renderer，避免直接争抢 stdout。`CodeIndex` 的索引进度通过 `ProgressListener` 注入，`/index` 应绑定到当前 renderer 输出流。
- Phase 22 开始，`InlineRenderer` 可绑定当前 `LineReader`；当 `LineReader.isReading()` 为 true 时，`Renderer.stream()` 的完整行输出优先通过 `LineReader#printAbove` 显示在输入行上方，未绑定 / 非读取态 / 测试路径回退到原 `PrintStream`。
- Markdown 表格渲染要按当前终端列宽分配列宽；长内容在单元格内部换行，不能依赖终端自动折行把整行表格打散。
- ReAct 正常结束后不再把 `📊 Token: ...` 打进正文区；token/cost/elapsed 会保留在底部强状态行，phase 回到 `idle`。
- 默认 CLI 启动路径应尽早建立 `Terminal -> LineReader -> Renderer`，启动 Banner、模型加载、MCP 启动、Skill summary、ReAct 提示和退出提示都应走 `Renderer.stream()`；除 fatal bootstrap / runtime API / legacy TUI 降级外，不要在交互主路径新增裸 `System.out.println`。
- 启动期 MCP 不得阻塞首屏：CLI 默认最多等待 8 秒（`PAICLI_MCP_STARTUP_WAIT_SECONDS` / `-Dpaicli.mcp.startup.wait.seconds` 可调），超时后保留未完成 server 为 `STARTING` 并后台继续初始化；`/mcp` 查看最新状态。
- `LineReader` 使用 `PaiCliHighlighter` 做输入实时高亮：slash 命令、`@` 引用、`@image:`、`@clipboard`、敏感词和明显危险 shell 片段会在编辑阶段被标记；不要把这类视觉提示混入最终提交文本。
- `LineReader` 使用 `PaiCliCompleter` 做上下文补全：`/model` provider、`/mcp` 子命令与 server、`/skill` 子命令与 skill name、`/task` / `/browser` / `/snapshot` 子命令、`@image:` 本地路径、本地 `@path` 和 MCP resource `@server:uri` 引用都应从同一个 completer 出口维护。
- 普通用户输入进入 Agent 前会先展开 MCP resource mention，再由 `LocalPathMentionExpander` 展开本地 `@path`：文件会内联为 `<file>` 块，目录会内联为 `<directory>` 列表；绝对路径或符号链接逃逸项目根时保持原文不展开。
- `LineReader` 使用 `PaiCliHistory` 持久化输入历史到 `~/.paicli/history/input.history`；如果 `paicli.history.file` / `PAICLI_HISTORY_FILE` 指向目录，也会自动使用该目录下的 `input.history`，避免把目录当文件读；默认忽略空白、重复、明显密钥/Bearer、base64 图片和超长输入，用户可用 `/history clear` 清空本机输入历史。
- JLine 交互升级计划记录在 `docs/phase-22-jline-interaction-upgrade.md`。
- 交互 REPL 是**事件循环**（ADR 0005，`AgentSession`）：主线程退化为输入泵，单一**串行执行器**线程独占主对话历史、跑所有 ReAct 主回合（前台 + 后台 push），是唯一写入者。ReAct 任务 = `postEvent(UserSubmit)` 后主线程 `pumpWaitUntilIdle` 等本轮跑完；`spawn_agent({background:true})` 立即返回 `task_id`、本轮继续，后台完成投递 `TaskDone` 唤醒执行器跑自治回合，输出经 `printAbove` 落在输入行上方；多个 `TaskDone` 在回合边界 drain 进同一回合。**Plan 模式**仍主线程同步执行。
- **HITL 审批走泵**（ADR 0005，`ApprovalPump`）：危险工具的 `requestApproval` 不在执行器/任务线程读终端，而是 **park 在 future**；**只有主线程**渲染审批（`renderer.promptApproval`）并 resolve——前台回合在 `pumpWaitUntilIdle` 里泵审批，后台任务审批经 `printAbove` 告知用户、回车后由输入泵 `drainPendingApprovals` 处理。子任务经 `HitlToolRegistry.createChildRegistry()` 共享同一个泵（危险工具照常上浮、权限 ≤ 派生者），每条审批用 `callerContext` 标注请求方（主 Agent / `task_N`）。「全部批准」会话级全局对所有 agent 生效。改 HITL/审批渲染时务必保证**读终端只在主线程**，否则与 `LineReader` 抢终端会死锁。
- **ESC 取消**（ADR 0005，`CancellationRegistry`）：每个任务 / 每个主回合各持<b>显式</b>取消令牌（`Agent.setCancellationToken`，不再靠 `InheritableThreadLocal` 继承）；ESC 在 `pumpWaitUntilIdle` 里被轮询到 → 取消当前前台回合 + **附着**任务（前台派生，登记为 attached）、reject 正 park 的审批；**游离**任务（后台派生 / 前台超时 auto-background 后 detach）不受 ESC 影响。取消是协作式的——在回合边界停（流式 LLM 回答不可中途打断，`GLMClient.chat` 无 mid-stream 取消）。
- **auto-background（#08，仅交互模式）**：前台派生超过 `paicli.task.auto-background-ms`（默认 ~2min）自动转后台——解除本轮阻塞返回「已转入后台」，任务继续跑、完成后经事件循环 push；同时 detach（转游离，不再受 ESC）。headless（`run --file`）下前台派生无限阻塞，不引入 auto-bg / push。
- **`/task`** 指向<b>内存级</b>会话内任务注册表（`TaskRegistry`，取代已退役的 SQLite `DurableTaskManager`）：`/task` 列出会话内任务及状态（运行中 / 空闲·可续派 / 失败），`/task <id>` 检视完整轨迹（走 `task_output`），`/task cancel <id>` 取消运行中的任务（`CancellationRegistry.cancelTask`）。会话绑定、不跨重启；跨重启/无人值守诉求归 `run --file` 与 `serve --http`（`RuntimeThreadStore`，未受影响）。
- `run` 子命令是同步无人值守入口：复用裸 `ToolRegistry`（工具自动放行 + 保留策略层）跑一次 `Agent.run`（`--strategy=react`），可选输出对齐 stream-json 语义的 NDJSON trace；不引入事件循环 / 泵 / 虚拟线程 push 等交互设施；墙钟超时由外层编排（shell `timeout`）兜底。

## 关键行为约束（Agent 必读）

### Memory

- 长期记忆只通过 `/save` 或用户明确要求保存；不要自动提取事实
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆必须可审计和可删除：`/memory list` / `/memory search <关键词>` / `/memory delete <id>` / `/memory clear`
- 两道压缩不要混淆：shortTermMemory 压缩 vs conversationHistory 压缩（后者是防 window 超限的关键）

### HITL + 策略层

- 拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard
- 用户无法批准策略拒绝的请求
- PathGuard 强制路径限定在项目根内
- CommandGuard 是辅助黑名单，不是主防线

### 生命周期 Hook（ADR 0012，wave 1）

- 统一 `HookDispatcher`（`com.paicli.hook`）在生命周期点分发 [[Hook 租户]]，给出四 outcome：`CONTINUE` / `BLOCK(reason)` / `ALLOW` / `ASK`；组合取最严（任一 BLOCK 短路、ASK 压过 ALLOW、context 聚合），空注册表零成本 no-op。`BLOCK` 是模型反馈级阻断（回灌原因续跑），不越过[[策略守卫]]；`ALLOW` 仅免除 HITL 审批。
- wave 1 只接线 `Stop`（`Agent.java` 收尾分支），首租户 = **收尾门（Finish Gate）**：本回合有成功写入、且最后一次写入后无验证命令成功执行时 BLOCK，把「先自证再交付」作为标记消息注入（同 LSP 诊断注入通道）并续跑。阻断消耗轮数，硬轮数上限（`paicli.react.hard.max.iterations`）始终兜底。
- 验证检测是可配置命令正则，只匹配 agent 自己在工作区跑的 `execute_command`（mvn/gradle/npm test/pytest/go test 等），**绝不引用任何外部评分测试集、绝不按评测实例参数化**。阻断记 `hook_block` 审计。
- 默认全关（safe-edit 先例），系统属性开启：`paicli.hook.stop.enabled`（Stop 分发总开关）+ `paicli.hook.finish_gate.enabled`（注册收尾门）+ `paicli.hook.finish_gate.verify_regex`（验证正则）+ `paicli.hook.finish_gate.block_cap`（默认 2）。
- 未落地：PreToolUse/PostToolUse/UserPromptSubmit 分发、读前写台账、shell 租户、`hooks.json` 配置与信任、`/hooks` 命令（后续 issue）。

### Plan 审阅交互

- `Enter` 执行 / `Ctrl+O` 展开 / `ESC` 取消 / `I` 补充重规划
- 方向键不应被误判为 ESC
- 涉及改动要连 raw mode 和回退路径一起看

### 并行工具

- 三条路径都走 `executeTools()`，不手写 for-loop
- 默认最多 4 个并发，结果保持原始顺序

### Web + Browser

- 每轮 system prompt 会注入当前日期/时区；包含“最新/当前/今天/今年/2026/趋势/新闻/版本”等时效性问题时，模型应先调用 `web_search`，不得在 `web_search` 可用时声称无法实时搜索。
- ReAct 会对上述时效性问题做确定性联网预检：进入第一轮 LLM 前先执行一次内置 `web_search` 并把结果注入本轮上下文；用户明确“不要联网/不用搜索”时跳过。
- 当前模型为 `step-3.7-flash*` 且自动/显式 `step_search` MCP 的 `web_search` / `web_fetch` 已就绪时，内置 `web_search` / `web_fetch` 会优先转调 StepSearch MCP；未就绪或调用失败时回退到原 SearchProvider / WebFetcher。
- 已知 URL 先 `web_fetch`，SPA/防爬墙 fallback 到 Chrome DevTools MCP
- 浏览器读取优先 `take_snapshot`，不默认 `take_screenshot`
- 公开页面不要提前切 shared 模式

### Skill

- system prompt 索引段注入三处提示词，上限 20 个 / 4KB
- `load_skill` → SkillContextBuffer → 下一轮 user message 前置注入

## 修改时的硬规则

### 1. 改行为 → 同步文档

`AGENTS.md` / `README.md` / `ROADMAP.md`（仅状态变化时）

### 2. 改命令入口 → 联动

`Main.java` + `CliCommandParser.java` + 测试 + `README.md` + `AGENTS.md`

未识别的 `/xxx` 在 CLI 层直接报"未知命令"，不回退给 Agent。

### 3. 改 Plan 审阅交互 → 联动

`Main.java` + `PlanReviewInputParser.java` + 测试 + 手工验证

### 4. 改工具集 → 联动

`ToolRegistry.java` + Agent/PlanExecuteAgent 提示词 + 可能 Planner 提示词 + 文档

### 5. 改模型/接口 → 联动

对应 Client + `LlmClientFactory.java` + `.env.example` + 文档

### 5.1 改 Embedding → `EmbeddingClient` + `VectorStore` + `.env.example` + 文档

### 5.2 改 Web/搜索 → `web/` 相关 + ToolRegistry + `.env.example` + 文档 + 测试

### 5.3 改 Memory → `MemoryManager` + `LongTermMemory` + `TokenBudget` + 测试 + 文档

### 5.4 改 HITL/策略 → `policy/` + ToolRegistry + HitlToolRegistry + 提示词 + `.env.example` + 文档 + 测试

### 5.5 改 MCP → `mcp/` + ToolRegistry + HITL + AuditLog + 提示词 + 文档 + 测试

### 6. 不提交 `.env` / 真实 API Key / `target/` 产物

### 7. 保持代码可读性，不过度抽象

## 验证路径

| 场景 | 命令 |
|------|------|
| 代码搜索工具 | `mvn test -Dtest=ToolRegistryTest,CodeSearchGoldenSetTest,ApprovalPolicyTest` |
| 命令解析 | `mvn test -Dtest=CliCommandParserTest,PlanReviewInputParserTest,MainInputNormalizationTest` |
| DAG/Plan | `mvn test -Dtest=ExecutionPlanTest` |
| 异步任务 substrate | `mvn test -Dtest=SpawnAgentToolTest,TaskRegistryTest,ChildToolRegistryTest,ParallelSpawnAgentTest,SendMessageTaskOutputTest` |
| TUI/终端 | `mvn test -Pphase16-smoke` |
| RAG | `mvn test -Dtest=CodeChunkerTest,CodeAnalyzerTest,VectorStoreTest,CodeIndexTest` |
| 常规回归 | `mvn test -Pquick` |

## 给新线程的导航

1. 先看本文件 → 2. `README.md` → 3. `Main.java` → 4. 按任务进入对应模块

| 任务类型 | 先看 |
|----------|------|
| CLI 命令 | Main.java + CliCommandParser.java |
| 规划/DAG | PlanExecuteAgent.java + Planner.java + ExecutionPlan.java |
| 工具调用 | ToolRegistry.java + Agent.java |
| 代码搜索 | ToolRegistry.java (`glob_files` / `grep_code` / `read_file`) |
| 模型/API | llm/*Client.java + LlmClientFactory.java |
| RAG 语义辅助 | CodeRetriever.java + CodeIndex.java + VectorStore.java |
| 异步任务 substrate | ForegroundAgentSpawner.java + agent/task/ + ToolRegistry.java (`spawn_agent`) |
| MCP | McpServerManager.java + McpClient.java |
| TUI/渲染 | render/Renderer.java + RendererFactory.java |

## 当前已知边界

以下在路线图但未交付：容器/VM 沙箱 / MCP OAuth + sampling + server 自动重启

不要把 `ROADMAP.md` 中"将来要做"误读成"现在已有"。

## 持续维护约定

形成稳定协作规则时直接补进本文件，不要只留在聊天记录里。详细实现细节补到 `docs/agents-reference.md`。

## Agent skills

### Issue tracker

Issue 以本地 markdown 形式存放在 `.scratch/<feature-slug>/` 下，不依赖外部平台。见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认五标签：needs-triage / needs-info / ready-for-agent / ready-for-human / wontfix。见 `docs/agents/triage-labels.md`。

### Domain docs

单上下文布局：根目录 `CONTEXT.md` + `docs/adr/`。见 `docs/agents/domain.md`。
