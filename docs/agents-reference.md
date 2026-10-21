# AGENTS Reference: Detailed Feature Behavior

This document contains detailed feature behavior descriptions, configuration reading orders, and implementation notes that were previously in `AGENTS.md`. Consult this when working on specific modules.

For the primary entry point, see `/AGENTS.md`.

---

## Configuration Reading Orders

### API Key

1. `~/.cafecli/config.json` 中对应 provider 的 `apiKey`
2. 环境变量：`GLM_API_KEY` / `DEEPSEEK_API_KEY` / `STEP_API_KEY` / `KIMI_API_KEY` / `FREELLMAPI_API_KEY`（Kimi 兼容 `MOONSHOT_API_KEY`）
3. 仓库当前目录下的 `.env`
4. 用户主目录下的 `.env`

### Persistence Locations

| 数据 | 默认路径 | 覆盖方式 |
|------|----------|----------|
| 长期记忆 | `~/.cafecli/memory/long_term_memory.json` | `-Dcafecli.memory.dir` |
| RAG 索引 | `~/.cafecli/rag/codebase.db` | `-Dcafecli.rag.dir` |
| 审计日志 | `~/.cafecli/audit/audit-YYYY-MM-DD.jsonl` | `CAFECLI_AUDIT_DIR` / `-Dcafecli.audit.dir` |
| Side-Git 快照 | `~/.cafecli/snapshots/<project_hash>/<worktree_hash>/.git` | `CAFECLI_SNAPSHOT_DIR` / `-Dcafecli.snapshot.dir` |
| 后台任务 | `~/.cafecli/tasks/tasks.db` | — |

### Snapshot Config

系统属性 > 环境变量 > 默认值：`cafecli.snapshot.enabled`(true) / `cafecli.snapshot.max`(50) / `cafecli.snapshot.excludes`(.git,.cafecli/snapshots,target,node_modules,dist,.idea,*.class,*.jar) / `cafecli.snapshot.dir`(~/.cafecli/snapshots)

### Embedding Config

环境变量 > 系统属性 > 默认值：`EMBEDDING_PROVIDER`(ollama) / `EMBEDDING_MODEL`(nomic-embed-text:latest) / `EMBEDDING_BASE_URL`(http://localhost:11434)

### Log Config

系统属性 > 环境变量/.env > 默认值：`CAFECLI_LOG_DIR`(~/.cafecli/logs) / `CAFECLI_LOG_LEVEL`(INFO) / `CAFECLI_LOG_MAX_HISTORY`(7) / `CAFECLI_LOG_MAX_FILE_SIZE`(10MB) / `CAFECLI_LOG_TOTAL_SIZE_CAP`(100MB)

### ReAct/SubAgent Budget Config

系统属性 > 默认值：`cafecli.react.token.budget`(Integer.MAX_VALUE) / `cafecli.react.stagnation.window`(3) / `cafecli.react.hard.max.iterations`(50)

设计取舍：长上下文模型默认不再以 80% x window 为硬限。死循环防护由 stagnation 检测（连续 3 轮相同工具调用）和 hardMaxIterations（50 轮）兜底。Token 显示行 `📊 Token: 已用 X / Y` 的 Y 是软提示，不代表强制限制。

### LLM HTTP Timeout Config

系统属性 > 默认值：`cafecli.llm.connect.timeout.seconds`(60) / `cafecli.llm.read.timeout.seconds`(300) / `cafecli.llm.write.timeout.seconds`(60) / `cafecli.llm.call.timeout.seconds`(600)

SSE 流式下 readTimeout 是两次 read 间最大间隔，GLM-5.1 生成大段 reasoning 时可能长时间静默，所以放宽到 300 秒。

### Web Search Provider Config

1. `SEARCH_PROVIDER` 显式指定 `zhipu` / `serpapi` / `searxng`
2. 未指定时按 Key 自动判断：`GLM_API_KEY` → zhipu / `SERPAPI_KEY` → serpapi / `SEARXNG_URL` → searxng
3. 都没有 → zhipu 占位

各 provider：zhipu(`GLM_API_KEY` + 可选 `ZHIPU_SEARCH_ENGINE`) / serpapi(`SERPAPI_KEY`) / searxng(`SEARXNG_URL`)

### Web Fetch Security (NetworkPolicy)

scheme 白名单(http/https) / 主机黑名单(localhost/loopback/link-local/site-local) / 响应体上限 5MB / 超时 30s / 限流 30次/60s

### MCP Config

1. 用户级：`~/.cafecli/mcp.json`
2. 项目级：`.cafecli/mcp.json`
3. 按 server 名 merge，项目级覆盖用户级

格式兼容 Claude Code：`command` + `args` = stdio，`url` + `headers` = Streamable HTTP。内置变量：`${PROJECT_DIR}`、`${HOME}`；其他 `${VAR}` 从系统环境变量、系统属性、项目 `.env`、用户 `~/.env` 读取。
检测到 `STEP_API_KEY` 时自动内置 `step_search` 远程 MCP（显式同名配置优先），用于 Step 3.7 Flash 的 `web_search` / `web_fetch` 优先代理。

### v2（实验，`-Dcafecli.v2=true`）

只给开发者在切换（ADR 0020，进度见 `.scratch/v2-architecture/`）之前对照 v1 用，不对用户承诺；不带开关时一切照旧。

- 开关只认 `-Dcafecli.v2=true`（`-Dpaicli.v2` 不生效）。`Main.main` 第一件事检查它，命中即整体交给 `app/SystemEntry`，v1 的装配一步都不走；属性桥接（`cafecli.*` → `paicli.*`）由 v2 入口自己装，复用的叶子模块照样读得到 `-Dcafecli.*`。
- 目前只有 `run --file <issue.md>`（同步，无事件循环 / 泵 / push），流与退出码同 v1：答案打到 stdout、退出码 0，v1 的结局文本（`❌ 达到硬轮数上限…`、`❌ 调用 LLM 失败: …`、`⏹️ 已取消当前任务。`）也作为答案这样打；参数错误同 v1 文案、退出码 2。交互模式、其他子命令、`--trace-file` 在 stderr 报 `not available in v2 yet`、退出码 2；`serve --http` 在 v2 已移除（同样 stderr、退出码 2）。
- 目前接 DeepSeek 与 fake provider（按脚本回放，格式见 `ai/fake/FakeScript`）；GLM / Step / Kimi / FreeLLMAPI 的适配器尚未移植，选中时在 stderr 报 not available、退出码 1。DeepSeek 无 Key 时提示 `DEEPSEEK_API_KEY` / `providers.deepseek.apiKey`。
- 工作区能力复用现有 `read_file` / `write_file` / `edit_file` / `list_dir` / `create_project` / `execute_command` / `glob_files` / `grep_code` 叶子，守卫与文件互斥锁仍在工具执行体内。命令逐行输出经 `ToolUpdate` 实时发布（单条命令也一样），写入的 `ToolEnd.result.details.diff` 带 `path` / `before` / `after`（新文件的 `before` 为 null），原叶子结果的其他字段与文本不变。
- 权限能力已接入 `beforeToolCall`：需审批的工具按模型原顺序全部问完，再开始执行；`Approver` 是可取消的阻塞接口，同一会话跨 Agent 串行请求。会话内支持按工具 / MCP server 全部放行，关闭审批时清空两类记忆并使先前审批的记忆授权失效；关闭期间或重新开启后才到达的旧答复仅批准本次调用。批准修改参数后，执行 / 事件 / 完成回调 / 审计使用新参数，assistant 历史保留模型的原始参数；守卫始终在叶子里执行。当前真实 `run --file` 同 v1 无人值守路径一样关闭审批、自动执行，不读 stdin；交互审批界面尚未接入。
- Hook 能力复用现有分发器与租户，开关仍默认全关（属性见下文 Lifecycle Hooks）。PreToolUse 在审批前按 BLOCK > ASK > ALLOW > CONTINUE 折叠：BLOCK 回灌类型化 `BLOCKED`；交互策略下 ASK 强制本次审批（即使审批关闭或已有记忆授权）、ALLOW 只跳过审批，守卫仍在执行时运行。无人值守同 v1 基础注册表只处理 BLOCK，忽略 ASK / ALLOW，也不记 hook-allow 审计。PostToolUse 只在工具体正常返回后分发（包含业务级 ERROR，不包含抛异常 / 守卫拒绝 / 未知调用），上下文经边界收件箱进入下一次请求。Pre/Post payload 使用会话唯一且跨回合稳定的 `session-UUID`；UPS / Stop 同 v1 不带 sessionId。台账 / Stop 分发器按 Agent 创建；台账跨回合保留，Finish Gate 的工具摘要与阻断计数每回合重置，摘要按模型工具顺序折叠。UserPromptSubmit 在普通输入准入后、核心日志与模型请求前运行；转向消息不触发它，阻断以 `SubmitResult.Blocked` 返回，headless 保留 v1 stderr 文案与退出码 1。四类 shell hook 均接收显式回合取消令牌。
- 审计能力在同步监听器里只排入不可变事件，由会话拥有的单写线程维护调用状态、构造 / 脱敏条目并落盘；`flush()` 等待已排入的记录，`AgentSession.close()` 按注册逆序关闭资源并排空审计，`run --file` 用 try-with-resources 保证退出前完成。会话构造失败也清理已注册的资源，某项清理失败仍继续关闭其余项。审计语义保持不变：拒绝 / 跳过记 `hitl` deny，抛出的策略拒绝记 `policy` deny，工具体正常返回仍记 allow（包括业务级 ERROR），抛异常或已注册工具的参数 JSON 解析失败记 error；未知工具不记执行审计。结果的结构化 `execution` 字段保留实际执行出处，审计不从状态或文本前缀反推。
- 配置（`harness/config/ConfigLoader`，来源与优先级同 v1，新旧名回退走 `ConfigHome`）：
  - provider：`-Dcafecli.provider`（回退 `-Dpaicli.provider`）> `CAFECLI_PROVIDER`（回退 `PAICLI_PROVIDER`）> 用户级 `config.json` 的 `defaultProvider` > `glm`。前两项是 v2 新增，空白值视为未设。随后沿用 v1 的别名归一化与按 Key 回退：先试选中的 provider，没有可用 Key / 未知 provider 时按 glm → deepseek → step → kimi → freellmapi 找第一个有 Key 的；选中了有 Key 但尚未移植的 provider 仍报 not available。
  - fake 脚本：`-Dcafecli.fake.script`（回退 `-Dpaicli.fake.script`）> `CAFECLI_FAKE_SCRIPT`（回退 `PAICLI_FAKE_SCRIPT`），相对路径按工作目录解析。v2 新增。
  - 审计目录：`cafecli.audit.dir`（回退 `paicli.audit.dir`）> `CAFECLI_AUDIT_DIR`（回退 `PAICLI_AUDIT_DIR`）> 用户配置目录下 `audit/`；相对目录按工作目录解析，保留 v1 的脱敏与字段截断。
  - Hook：事件 / 租户开关与 Finish Gate 参数在加载时固定到 `HooksConfig`，系统属性支持 `paicli.*` 回退。`hooks.json` 仍按用户级再项目级加载，项目配置保留 SHA-256 指纹信任与确认端口，headless 跳过未信任项。命令变量显式按环境 > 系统属性 > 项目 `.env` > 用户 `.env` 展开，`${HOME}` / `${PROJECT_DIR}` 来自加载输入。信任 / 阻断 / 免审批观察只排队到会话拥有的审计写入者，关闭会话时排空。
  - 硬迭代上限：同 v1 的 `cafecli.react.hard.max.iterations`（只认系统属性，默认 50）。
  - 禁用编辑：`cafecli.edit_file.disabled`（回退 `paicli.edit_file.disabled`）在加载时固定进类型化配置；去空白后 `true` / `1` / `yes`（不区分大小写）禁用，其他值不禁用。禁用后既不提供定义，也不能执行该工具。命令超时仍为构造输入（默认 60 秒，无新增配置键）；批次超时由工厂传入 `max(命令超时 + 5, 90)` 秒，仅多调用批次使用，单调用由叶子自己的超时控制。
- 真实 provider 的 `apiKey` / `model` / `baseUrl` 读取顺序同 v1：用户级 `config.json` 的 `providers.<name>` 非空字段 > 对应环境变量 > 工作目录 `.env` > 用户主目录 `.env`；`.env` 不做引号剥离或 shell 展开。Kimi 兼容 `MOONSHOT_*`（在 `KIMI_*` 环境与 `.env` 都无值后才读）。项目级 `config.json` 不参与此配置。
- DeepSeek 默认 `deepseek-v4-flash`、固定请求 `https://api.deepseek.com/chat/completions`；仍不使用配置里的 `baseUrl` / `temperature` / `maxTokens`，沿用 v1 实际 wire 行为。HTTP 超时读取现有 `-Dcafecli.llm.{connect,read,write,call}.timeout.seconds`（旧前缀回退），默认 60 / 300 / 60 / 600 秒。取消会中止等待响应头或流式响应体的 HTTP 请求。
- `ai/ModelRegistry` 内置五家默认模型的元数据；用户级 `config.json` 可加 `models` 数组覆盖或添加记录，例如 `{"models":[{"provider":"deepseek","id":"deepseek-v4-flash","contextWindow":500000,"maxOutputTokens":8192}]}`。省略字段继承该 provider 的默认值；`features` 若填写则整体替换，取 `REASONING` / `IMAGE_INPUT` / `PROMPT_CACHING` / `TOOL_CALL_STREAMING`。未列出的模型 id 沿用该 provider 的默认元数据，保持 v1 可任意指定模型 id 的行为。元数据上限不额外变成请求参数。其他四家只有元数据，尚不启用它们的适配器。
- 示例：`java -Dcafecli.v2=true -Dcafecli.provider=fake -Dcafecli.fake.script=script.json -jar target/cafecli-1.0.0.jar run --file issue.md`

---

## Detailed Feature Behavior

### ReAct Mode

- 主入口：`Agent.java`
- 退出条件由 LLM 自决（不返回 tool_calls 即结束）
- `AgentBudget` 三种兜底：token 超预算 / 连续 3 轮相同调用 / 50 轮硬上限
- 流式输出 reasoning_content + content，经类型化渲染事件交给 renderer（见「Inline Rendering」段）；完成的
  reasoning 以一行折叠头进[[冻结回卷]]，展开只在[[转写浏览层]]重建
- **迭代边界上有三条前置注入通道**，都在下一次 LLM 调用前写一条清晰标记的 user 消息，空则零成本 no-op，
  **顺序有意义**：`injectPendingLspDiagnostics()`（`[LSP 诊断注入]`）→ `injectPendingHookContext()`
  （`[Hook 上下文 / PostToolUse]`，收尾门的 BLOCK 也走这条）→ `injectPendingUserSteering()`
  （`[用户转向 / 回合中提交]`，离下一次调用最近，因为它是用户说的话）。前两条从 `ToolRegistry` drain，
  第三条从 `SteeringInbox` drain。**注入粒度就是迭代边界**——流式回答不可中途打断，与[[取消键]]同因

### Long Context Engineering

- `ContextProfile` 计算 short/balanced/long 模式
- GLM-5.1: 200k / DeepSeek V4: 1M / StepFun: 256k / Kimi K2.6: 256k / FreeLLMAPI: 128k
- long 模式(>=100k)：跳过 Memory 自动摘要，search_code 语义辅助 topK=20，MCP resources 自动索引；精确代码定位仍优先实时 glob/grep/read
- prompt caching：能力声明 + cached usage 解析

### Memory System

- 两道压缩：
  1. `ContextCompressor` 压缩 shortTermMemory
  2. `ConversationHistoryCompactor` 压缩 conversationHistory（真正发给 LLM 的消息）
- 第二道压缩切割在 user message 边界，保留最近 3 个 user 起算的尾部
- 三条路径(ReAct/Plan/SubAgent)都接入第二道压缩
- 长期记忆只通过 `/save` 或用户明确要求保存
- 长期记忆只保存跨会话稳定事实，不保存临时指令；默认项目级作用域，跨项目通用偏好才用 global
- 长期记忆管理命令：`/memory list`、`/memory search <关键词>`、`/memory delete <id>`、`/memory clear`

### 会话内异步任务（取代已退役的 Multi-Agent 编排）

确定性三角色编排（`AgentOrchestrator` / `SubAgent` / `/team`）已退役（ADR 0004），由主 Agent 自主派生取代。
术语见 `CONTEXT.md`「Agent 编排」段。

- 三个工具：`spawn_agent`（前台阻塞 / 后台立即返回 `task_id`）、`send_message`（续派空闲任务）、
  `task_output`（检视完整轨迹）。多个前台派生经工具批次并行。
- 每任务一个 `ToolRegistry`（ADR 0007）+ 独立对话历史；共享同一个 `HookDispatcher`、`ApprovalPump` 与
  `MemoryManager`。审批的 `callerContext` 标出主 Agent 还是 `task_N`，全部放行仍是会话全局。
- **auto-background（仅交互模式）**：前台派生超过 `cafecli.task.auto-background-ms`（默认 ~2min）自动转
  后台——解除本轮阻塞返回「已转入后台」，任务继续跑、完成后经事件循环 push，同时 detach（不再受取消键）。
  headless（`run --file`）下前台派生无限阻塞，不引入 auto-bg / push。
- `/task` 指向**内存级**会话内 `TaskRegistry`（SQLite `DurableTaskManager` 已退役）：`/task` 列出任务与状态、
  `/task <id>` 检视轨迹、`/task cancel <id>` 取消运行中的任务。会话绑定、不跨重启。

### HITL System

- 危险工具：write_file(中) / execute_command(高) / create_project(中) / revert_turn(高)；全部 `mcp__` 工具
  默认走 HITL + AuditLog。
- **审批经[[输入框]]提交**（不是单键提示器，ADR 0005 + `ApprovalPump`）：`y`/`yes` 批准、`n [原因]` 拒绝、
  `a`（`a server` 按 MCP server 维度）全部放行、`s` 跳过、`m {json}` 改写参数，与普通提交同一条通路。
  **空提交表示「再问一次」**——重新呈现审批卡而不产生决定，误触回车既不是批准也不是拒绝；无法识别的
  回答同形。没有决定时不从队列取走（`peekPending()`），请求方继续 park。
- 请求方 park 在**没有超时**的 future 上，所以[[取消键]]必须拒掉待决审批才能真的停下回合。
- **入队通知先于「可取出」**：`requestApproval` 先 `pendingNotifier.accept(request)` 再 `queue.add(pending)`；
  反过来时正在 poll 的线程可能抢先取走并 resolve，通知就讲了一条已不存在的审批。右提示符因此**不能**直接
  读 `hasPending()`（被通知那一刻队列还是空的），REPL 记的是卡片生命周期。

### Lifecycle Hooks (ADR 0012, wave 1)

决策与边界见 ADR 0012 与 `CONTEXT.md`「生命周期 Hook 与策略层」；**守卫绝对**那条在 `AGENTS.md`。

- 接线点：`Stop`（`Agent.java` 收尾分支）、`PreToolUse`（`HitlToolRegistry` 执行路径顶部、审批之前）、
  `PostToolUse`（`ToolRegistry` 执行 sink，覆盖单 / 并行 / MCP 全部路径）、`UserPromptSubmit`（CLI 主路径
  mention 展开后、跑 Agent 前；**斜杠命令不触发**）。分发器由 `Agent` 构造，子任务经拷贝构造共享同一个；
  无租户则不注入，默认路径零成本。
- **收尾门（Finish Gate，Stop）**：本回合有成功写入、且最后一次写入后无验证命令成功执行时 BLOCK，把
  「先自证再交付」作为标记消息注入并续跑。「成功」= `ToolOutput.status()` 为 `OK`，`execute_command` 另需
  退出码 0（`Agent.hookToolSucceeded`，ADR 0019）——被策略 / HITL / Hook 拒掉或业务失败的写入不算写入。验证检测是可配置命令正则，只匹配 agent 自己在工作区跑的
  `execute_command`（mvn/gradle/npm test/pytest/go test 等），**绝不引用任何外部评分测试集、绝不按评测
  实例参数化**。阻断消耗轮数，硬轮数上限 `cafecli.react.hard.max.iterations` 始终兜底。
- **读前写台账（Read-before-Write Ledger）**：`read_file` 成功记录 path + 内容指纹；写类工具执行前——
  目标不存在→放行、存在但没读过→BLOCK「先读」、读后文件已变→BLOCK「陈旧，重读」、读后未变→放行；
  自己成功写入刷新指纹。作用域**按 agent**（`taskId` 做键，子任务的读不满足主 Agent，反之亦然）。
- **Shell 租户（`hooks.json`）**：`~/.cafecli/hooks.json`（用户级）/ `.cafecli/hooks.json`（项目级），
  `{event: [{matcher, hooks:[{type:"command", command, timeout, statusMessage}]}]}`，schema 对齐 Claude Code。
  matcher 是工具名正则（缺省 = 全部），只接 `type:"command"`，`${VAR}` 展开复用 `mcp.json` 机制。执行契约：
  payload JSON 走 stdin + `CAFECLI_PROJECT_DIR`；exit 0 = CONTINUE（stdout 可带 JSON 决定超集
  decision/reason/additionalContext/statusMessage）、exit 2 = BLOCK（stderr 作原因）、其他退出码 = 告警不
  阻断；超时（默认 60s，硬顶 10min）与崩溃 fail-open；进程随回合取消令牌走。**hook 命令免 CommandGuard 与
  逐次 HITL**（信任在配置级）。由 `Agent.installConfiguredHooks(...)` 加载（单元测试不调用以保确定性）。
- **项目 Hook 信任**（`HookTrustStore` + `HookTrustConfirmer`）：用户级与内建租户默认受信；**项目级默认不
  受信**——按内容 SHA-256 指纹，未钉住则交互态询问一次，批准→钉到 `~/.cafecli/hook-trust.json`、拒绝→本
  会话跳过；内容任何变更使指纹失配→重新确认。headless **从不弹窗**：已钉住的运行、未钉住的静默跳过。
- **UserPromptSubmit**：无内建租户，只服务 shell 租户。BLOCK 拦下提交、原因给**用户**（不是模型），Agent
  不运行；`additionalContext` 追加到实际交给 Agent 的 prompt。headless 下 BLOCK 使本次 `run` 失败。
- **默认全关**（safe-edit 先例）。事件分发总开关（shell 与内建租户共用）：`cafecli.hook.stop.enabled` /
  `cafecli.hook.pre_tool_use.enabled` / `cafecli.hook.post_tool_use.enabled` /
  `cafecli.hook.user_prompt_submit.enabled`。租户注册开关：`cafecli.hook.finish_gate.enabled`
  （+ `cafecli.hook.finish_gate.verify_regex` / `cafecli.hook.finish_gate.block_cap`，默认 2）、
  `cafecli.hook.read_ledger.enabled`。台账需三者齐开：`pre_tool_use` + `post_tool_use` + `read_ledger`；
  shell hook 需对应事件总开关开。
- `/hooks` 列出本会话已加载的每个 hook：事件、名称、来源（builtin / user / project）、matcher、开关状态、
  本会话 fire/block 计数。审计事件：`hook_block` / `hook_allow` / `hook_trust`。
- **未落地（wave 2）**：`updatedInput` / `updatedToolOutput` 改写、异步 hook、agent-type verifier hook、
  `SubagentStart/Stop` 事件、把 safe-edit / per-edit LSP 迁移成 hook、默认开启内建租户。

### HITL Enhancement (Policy Layer)

- `PathGuard`：路径限定在项目根内（绝对路径外逃 / `..` 穿越 / 符号链接逃逸）
- `CommandGuard`：fast-fail 黑名单（sudo/rm -rf/mkfs/dd/fork bomb/curl|sh 等）
- `ResourceLimit`：write_file 5MB / execute_command 60s + 8KB 输出
- `AuditLog`：JSONL 字段 timestamp/tool/args/outcome/reason/approver/durationMs
- 拦截顺序：HitlToolRegistry → ToolRegistry → 策略层。用户无法批准策略拒绝的请求

### Parallel Tool Execution

- `executeTools()` 固定线程池并行，默认最多 4 个并发
- 返回结果保持原始顺序
- 主 Agent 与子任务都走 executeTools()

### Web Capabilities

- `web_search`：SearchProvider 接口，返回 SearchResult 列表
- `web_fetch`：NetworkPolicy → WebFetcher → HtmlExtractor，SPA/防爬墙返回空正文 + 边界提示
- ReAct freshness preflight：用户输入命中最新/当前/今天/今年/2026/趋势/新闻/版本等时效性关键词且未显式禁止联网时，Agent 在第一轮 LLM 前执行一次内置 `web_search`，并把结果作为 `联网预检结果` 注入当前对话。
- StepSearch 优先级：当前模型 provider=`step` 且 model 以 `step-3.7-flash` 开头，并且自动/显式 `mcp__step_search__web_search` / `mcp__step_search__web_fetch` 已注册时，内置 `web_search` / `web_fetch` 会先代理到 StepSearch MCP；MCP 未就绪或返回不可用结果时回退原实现。
- JS 渲染 fallback 到 Chrome DevTools MCP

### MCP Protocol

- stdio + Streamable HTTP 双 transport
- 工具注册为 `mcp__{server}__{tool}`
- McpSchemaSanitizer 清洗 inputSchema
- 所有 mcp__ 工具默认走 HITL + AuditLog
- `structuredContent` 保留为 typed `ToolOutput` block；单个结构化 MCP 结果 compact JSON ≥25k chars 时落 `.cafecli/tool-results/{session}/{scope}/{tool_call_id}.json`，tool result 只给模型 schema 签名、前 3 条样本/omitted counts、文件路径与读取提示；MCP 大文本结果 ≥25k chars 落 `.txt` 并只回 head/tail digest + 路径；同批次 MCP inline payload 合计超过 125k chars 时按大小降序落盘直到预算内；`jq` 可用才建议 `jq`，否则建议 `grep_code` + `read_file` offset/limit；显式 `grep_code.path=.cafecli/tool-results/...` 可检索结果文件；内联 digest 脱敏敏感 key，落盘文件 raw；scope 默认 main，子任务 task_N，`/clear` 清 main scope，任务回收清 task scope，正常退出清 session，小结果内联
- resources 双轨：虚拟工具 + @-mention 输入层
- CLI 首屏默认只等待 MCP 启动 8 秒，慢 server 后台继续初始化并保持 `starting`，用 `/mcp` / `/mcp logs <name>` 追踪
- notifications 路由：tools/list_changed → 工具全量替换，resources 变化 → cache 失效

### Chrome DevTools MCP

- 默认 server：chrome-devtools，`npx -y chrome-devtools-mcp@latest --isolated=true`
- `/browser connect`：切到 --autoConnect 复用登录态 Chrome
- `/browser connect <port>`：旧式 CDP 端口路径
- `/browser disconnect`：切回 isolated
- 敏感页面策略：改写型工具必须单步 HITL，不复用全部放行
- shared 模式 close_page 只允许关闭 Cafecli 创建的 tab
- 使用纪律：已知 URL 先 `web_fetch`，SPA / 防爬墙才 fallback 到本 MCP；页面读取优先 `take_snapshot` 而不是
  默认 `take_screenshot`；公开页面不要提前切 shared 模式

### Skill System

- 三层加载：jar 内置 < 用户级 ~/.cafecli/skills/ < 项目级 .cafecli/skills/
- frontmatter：name(必填) / description(必填,<=500) / version / author / tags
- system prompt 索引段注入到三处提示词末尾，上限 20 个 / 4KB
- load_skill 工具把 SKILL.md 正文(5KB 截断)写入 SkillContextBuffer
- buffer 一次性消费，最多 3 个 skill body

### Inline Rendering and Terminal Interaction

**架构与理由都不在这里**：区域模型与不变式见 `CONTEXT.md`「终端渲染」段，决策与被否决方案见
ADR 0010（缩放 epoch / 保留窗口 / 浏览层）、0011（类型化块）、0013（浏览层折叠）、0014（输入框常驻，
LineReader 交出画笔）、0016（输入框遗留问题）、0017（输出分类学 / 工具块）。本节只列**从代码之外读不到
的操作性细节**。

#### 形态与开关

- 两个渲染器：`InlineRenderer`（默认）/ `PlainRenderer`，用 `CAFECLI_RENDERER=inline|plain` 选。`plain`
  是显式降级逃生口。
- 已退役（ADR 0015）：`LanternaRenderer` 与 `tui/` 整包。`CAFECLI_RENDERER=lanterna|tui` 打一条「已移除」
  提示后回退 inline（不抛异常）；旧 `CAFECLI_TUI` / `PAICLI_TUI` 无任何效果。
- `CAFECLI_NO_STATUSBAR=true` 禁用底部状态坞；`NO_COLOR=1` 禁用 ANSI 颜色。

#### 终端构造

- 交互路径的 system `Terminal` 一律从 `TerminalCapabilities.systemTerminalBuilder()` 建，默认
  `graphemeCluster(false)`：JLine 4 会在 `xterm*` 上写 DECRQM 探测 `ESC[?2027$p`，Apple Terminal 不认 CSI
  intermediate（`$`），会把尾字符 `p` 回显成首屏 Banner 上方一个孤立的 "p"。该探测只影响 JLine 内部宽度
  语义（我们一直按 wcwidth 计算），显式设 `-Dorg.jline.terminal.graphemeCluster` 时尊重用户配置。
- 行编辑器是 `CafeCliLineReader`（`render/inline/`）。**`LineReaderBuilder` 不可用**（final + 私有构造 +
  `build()` 硬编码 `LineReaderImpl`），`Main` 与 `RenderReproDriver` 两处建构点都走
  `CafeCliLineReader.create(...)` 手工构造 + 逐项 setter。**`variables` 只能走构造器**——
  `INPUT_RC_FILE_NAME` 与 `AMBIGUOUS_BINDING`（设为 100ms）在 `LineReaderImpl` 构造期就被读走，事后
  `setVariable` 无效。`SECONDARY_PROMPT_PATTERN` 不受此限（每次重排才读）。
- 降级路径：`CafeCliLineReader.supported(terminal)` 为假（dumb / 过小终端）或 renderer 不是
  `InlineRenderer` 时**不安装子类**，用原生行编辑器 + `printAbove`，行为与交出画笔之前一致。

#### 启动首屏

- 先 `Renderer.start()`（几何观察与 WINCH 接线都在这里建立——**构造了但没 start 的 renderer 不响应缩放**），
  再经 `InlineRenderer.installStartupScreen(...)` 挂到 `LineReader.CALLBACK_INIT`，首次进入输入时一次性
  显示 Banner + tips。**不要在 `readLine` 前裸写 stdout**，否则 logo 会被首次重绘滚出可视区。
- Banner 是无右边框的简洁布局（避免 CJK/ANSI 字宽导致竖线错位）：π 主题彩色 logo + 模型 / MCP / Skill /
  ReAct 状态 + 三条 getting-started tips，不再把 MCP server 明细刷成启动日志。
- 咖啡杯配色：蒸汽 `AnsiStyle.steam()`（暗灰）+ 杯体 `AnsiStyle.cupLayer(text, 0..3)`（layer 0 杯口奶油白 →
  layer 3 杯底深咖啡棕；256 色 223/180/137/94 同一条暖棕 ramp，16 色退化成亮白 → 亮黄 → 黄 → 暗黄）。
- 启动期 MCP 不得阻塞首屏：默认最多等 8 秒（`CAFECLI_MCP_STARTUP_WAIT_SECONDS` /
  `-Dcafecli.mcp.startup.wait.seconds`），超时后未完成 server 保持 `STARTING` 并后台继续初始化，`/mcp` 看状态。

#### 输入框（JLine 集成）

- 左提示符 `>`；右提示符按状态切换：空闲 `message / @path / @image` → 回合中 `steer · esc 停止` →
  审批待决 `y / n / a`，由 `setRightPromptSupplier` 在每次重排时求值。**渲染线程从不调用重排**——那会让
  合成一帧依赖输入线程。
- **手动换行必须显式绑**（`InteractiveRepl.bindNewlineKey`）：终端上「换行」没有一个键，JLine 默认一个编码
  都不认，转义序列会落到 `self-insert` 直接打进缓冲区。已收：`ESC CR` / `ESC LF`（Option+Enter，macOS 上
  最可靠）、`CSI 13;2u` / `CSI 13;5u`（fixterms/kitty）、`CSI 27;2;13~` / `CSI 27;5;13~`（xterm
  `modifyOtherKeys`）。续行提示符改成**两个空格**与首行 `> ` 对齐。**明码标价的代价**：Terminal.app 给
  Shift+Enter 发的是裸 `CR`，与 Enter 逐字节相同，进程内无法区分——那一档要么终端里改绑、要么 Option+Enter。
- `Ctrl+L`（clear-screen）**不重绑也不提供**，走 JLine 原生行为。
- **不装历史自动建议**（`AutosuggestionWidgets`）：它把上一条历史以暗色接在光标后，读起来像上次输入的残留。
- `CafeCliHighlighter` 做输入实时高亮（slash 命令 / `@` 引用 / `@image:` / `@clipboard` / 敏感词 / 危险
  shell 片段）；这类视觉提示**不得混进最终提交文本**。
- `CafeCliCompleter` 是唯一补全出口：`/model` provider、`/mcp` / `/skill` / `/task` / `/browser` /
  `/snapshot` 子命令、`@image:` 本地路径、本地 `@path`、MCP resource `@server:uri`。
- `CafeCliHistory` 持久化到 `~/.cafecli/history/input.history`；`cafecli.history.file` /
  `CAFECLI_HISTORY_FILE` 指向目录时自动用该目录下的 `input.history`。默认忽略空白、重复、明显密钥/Bearer、
  base64 图片和超长输入；`/history clear` 清空。
- **色彩档位分两半**（`InputBoxChrome.Palette`）：`fromCapabilities(terminal)` 只看终端能力，`of(terminal)`
  在它之上再乘进程级颜色开关。**测试请断言 `fromCapabilities`，不要断言 `of` 的具体转义序列**——
  `AnsiStyle.isEnabled()` 背后是一个 `static final`、每 JVM 只算一次的快照，而
  `TerminalMarkdownRendererTest` 在静态初始化里设 `paicli.render.color=false`，于是「哪个测试类先加载
  `AnsiStyle`」决定整个 JVM 的档位。断言 `of` 只在全量顺序下成立，是 flake 而不是判据。

#### 提交与转写

- 提交（普通任务与斜杠命令）经 `SubmittedPromptBlock` 回写为暗色整行块：底纹补齐到终端宽度，紫色 `›`
  **只在首行**，续行缩进两列对齐正文；折行由块自身用 JLine `columnSplitLength` 按列宽完成（CJK 折在正确
  的列），不依赖终端自动折行，故底纹在折行处不断裂。配色按 `TerminalCapabilities.supports256Colors` 分支：
  256 色走背景底纹并补齐宽度，16 色退化为前景色且不补齐，禁用 ANSI 时输出纯文本。空白输入不产出块。
  保留的是**源文本**（`SubmittedPromptItem`），由缩放重置按新宽度重新渲染。
- mention 展开（MCP resource → 本地 `@path`）发生在转写块**之后**：转写块用的是展开前的原文。
  `LocalPathMentionExpander` 把文件内联为 `<file>` 块、目录内联为 `<directory>` 列表；绝对路径或符号链接
  逃逸项目根时保持原文不展开。
- `/clear` 清 conversationHistory + shortTermMemory + 待注入 `SkillContextBuffer` + main scope 的 MCP 工具
  结果，并重建不含上一轮检索记忆的 system prompt；长期记忆保留。
- 折叠头是纯文本标签（`▎` 色条已退役），块类型靠文案分辨。重构样式时保持这些断言子串同步：
  `思考 N 行` / `code: <lang>` / `Update <path>  +A -D` / `ReadFile(...)`。**亮度分三档**：
  [[工具块]]调用头用普通前景色（`BlockHeader.identity`），思考 / 代码 / diff 头暗灰（`.label`），
  `… +N 行 (ctrl+o 查看全部)` 最暗（`.footnote`）。溢出文案只有一处常量：`render/BlockOverflow.line(int)`。
- Markdown 表格按当前终端列宽分配列宽，长内容在单元格内部换行（CJK 按显示宽度算）。其它顶层块之间恰好一个
  空行；列表 / 引用内部紧凑；标题与分隔线不再额外出线；围栏代码无外框、无四空格缩进。
- 状态坞（`BottomStatusBar`）**不是 JLine `Status`，而是活区尾部行，由渲染线程画出**（ADR 0014）。内容：
  上层模式 + MCP/Skill 摘要，下层 Auto Model / model / phase / ctx 百分比 / token / cost / elapsed / cwd。
  **`ctx` 与 `in/out/cache` 不要混用**：前者是当前仍会带入下一轮请求的上下文估算，后者是最近任务的 LLM
  调用统计。`Ctrl+Y` 在空闲与回合中都复用 `/hitl on|off` 的状态通路切换 HITL / YOLO 并立即刷新。
- ReAct 正常结束后不把 `📊 Token: ...` 打进正文区；token / cost / elapsed 留在状态坞，phase 回 `idle`。
- `CodeIndex` 经 `ProgressListener` 上报索引进度，`/index` 绑定当前 renderer 输出流；异常细节写 logger。

#### 已知 JLine 耦合点

`CaptureDisplayInterceptionGuardTest` 整体钉住六个：`LineReaderImpl` 非 final 且构造器 public、
`display` / `lastStatusSize` protected、`redisplay(boolean)` / `doCleanup(boolean)` protected、
`Display.update(List,int,boolean)` public 且是唯一写入者。**第七个是 WINCH**：`readLine` 期间
`LineReaderImpl` 安装自己的 handler 且不向下链接，而输入框是常驻的，所以
`CafeCliLineReader.handleSignal` 覆写后把 WINCH 转交 `InlineRenderer.onGeometrySignal`。
**给输入框加左右边框会产生第八个**（要让行编辑器按一个不是终端宽度的宽度排版），已明确否决——见 ADR 0016 D6。

### LSP Diagnostics (Phase 17)

- write_file 成功后对 Java 文件做 JavaParser 语法诊断
- 诊断作为合成 user message 注入下一轮 LLM 请求
- `CAFECLI_LSP_ENABLED=false` 关闭

### Git Side-History Snapshot (Phase 18)

- side-git 在 ~/.cafecli/snapshots/ 维护独立仓库（JGit，不依赖系统 git）
- pre-turn 同步，post-turn 异步
- revert_turn 纳入 HITL/AuditLog，恢复前先创建 pre-restore 快照

### Prompt Layering (Phase 19)

- 组装顺序：base → personality → mode → approval → runtime_context → project_context → skills → context_mgmt → handoff
- runtime_context 每轮注入当前日期和系统时区，供最新信息、相对日期和联网决策使用
- 覆盖优先级：jar 内置 < 用户级 ~/.cafecli/prompts/ < 项目级 .cafecli/prompts/
- 必要校验：base.md 和最终 prompt 必须包含 `## Language`

### Runtime API + 无人值守入口 (Phase 20)

- Runtime API: `serve --http --port 8080`，仅 127.0.0.1，需 API Key。端点：`POST /v1/threads` /
  `POST /v1/threads/{id}/turns` / `GET /v1/threads/{id}/events`；线程存储是 `RuntimeThreadStore`。
- `run --file issue.md`：同步无人值守单任务，复用裸 `ToolRegistry`（工具自动放行 + 保留策略层）跑一次
  `Agent.run`（`--strategy=react`，默认即 react），可选 `--trace-file` 输出对齐 stream-json 语义的 NDJSON。
  不引入事件循环 / 泵 / 虚拟线程 push；墙钟超时由外层编排（shell `timeout`）兜底。
- 会话内任务（`/task`、`spawn_agent`）见上文「会话内异步任务」；SQLite `DurableTaskManager` 已退役。

### Image Input (Phase 21)

- ContentPart 支持图片 block（base64 + mimeType）
- 叶子侧（`ToolOutput` / MCP 结果 / `ImageReferenceParser.parse`）只产出 `image/ImagePart`（base64 + mimeType），不碰 `LlmClient`；
  v1 在组装消息时转换：`ContentPart.image(ImagePart)`、`llm/UserMessages.fromInput`（`@image:` 输入 → user 消息）
- ImageProcessor：铺白底/缩放 2000x2000/压缩 5MB
- 输入：`@image:file:///path.png` / `@image:/path.png` / `@image:relative.png`
- GLM-5V-Turbo 通过 `/model glm-5v-turbo` 切换
- 历史 image payload 替换为文本占位，避免旧截图消耗上下文

---

## Core File Descriptions

### Main.java
**只做装配**：.env 读取 / 日志初始化 / 构造终端、渲染器、行编辑器、事件循环、审批泵、取消注册表、键绑定，
然后交给 `InteractiveRepl`。它自己不读终端（CI 断言 `terminal.reader()` 与 `enterRawMode()` 零出现）。

### cli/InteractiveRepl.java
交互 REPL 本体：读输入的循环 / 键绑定 / 提交路由 / 三条退出路径的道别。**不构造终端 / 渲染器 / 事件循环，
只接受它们**，所以能在无真实 TTY 时用建在管道流上的终端驱动。**提交路由的顺序有意义**：有审批待决 →
应答审批；斜杠命令 → 交给 `cli/SlashCommandExecutor`；否则 mention 展开 + UserPromptSubmit hook 之后先试
`session.steer(...)`，接住了就只回显（**不** `beginTurn`——[[转向消息]]不是一个回合），没接住才
`postEvent(UserSubmit)`。取消的唯一实现在 `cli/SessionInterruptGuard.interrupt()`（三个入口共用）。

### Agent.java
ReAct 主循环 / 对话历史 / 工具调用与结果回灌。（`AgentOrchestrator` 与 `SubAgent` 已随 ADR 0004 删除。）

### ToolRegistry.java
12 个核心内置工具（`edit_file` 可被 `cafecli.edit_file.disabled` 关闭）+ 记忆与 Skill 2 个（`save_memory` / `load_skill`）+ 浏览器 3 个（`browser_connect` / `browser_disconnect` / `browser_status`）+ 异步任务 3 个（`spawn_agent` / `send_message` / `task_output`）+ MCP 动态工具 / executeTools() 并行入口。工具定义与调用的数据类型（`Tool` / `ToolParam` / `ToolExecutor` / `ToolInvocation` / `ToolExecutionResult`）与有副作用工具清单 `MutatingTools` 是同包的独立叶子类型，不嵌在 registry 里；`McpServerManager` 经 `mcp/McpToolSink` 回写 MCP 工具，registry 实现该接口。代码理解默认路径是 `glob_files` / `grep_code` / `read_file` 现用现查；`read_file` 裸读大文件默认超过 256KB 会报错并提示改用 `offset`/`limit`、`grep_code` 或 `jq` 精确检索，阈值可用 `cafecli.read_file.max_unranged_bytes` / `CAFECLI_READ_FILE_MAX_UNRANGED_BYTES` 调整。`grep_code` 优先走 ripgrep 并按 `max_results` / `head_limit` / `max_chars` 渐进返回，`search_code` 保留为 RAG 语义辅助。确定性搜索链路的回归样例见 `docs/code-search-golden-set.md`。`execute_command` 的实现在 `CommandTool`，`glob_files` / `grep_code` / `search_code` 在 `SearchTools`，`read_file` / `write_file` / `edit_file` / `list_dir` / `create_project` 在 `FileTools`（`edit_file` 的纯计算内核在同包的 `EditFileTool`），`web_search` / `web_fetch` 在 `WebTools`，`browser_connect` / `browser_disconnect` / `browser_status` 在 `BrowserTools`，`save_memory` / `load_skill` / `revert_turn` 在 `SessionTools`，`spawn_agent` / `send_message` / `task_output` 在 `TaskTools`。前三组经 `ToolEnv`（一组 supplier）在**每次调用时**现取项目根 / 路径围栏 / 命令输出观察者 / 文件写入观察者 / LSP 管理器——子任务 registry 与 `setProjectPath` 都在注册之后才换掉这些状态，注册期抓值会把围栏落在 JVM 工作目录上；后四组只有自己用到的协作者（web 服务与模型选择、浏览器连接器、记忆写入器、Skill 索引与缓冲、快照服务、`AgentSpawner`）不进 `ToolEnv`，以 supplier 传给各自的构造器，同样调用时现取（均由 `ToolEnvWiringTest` 钉住）。

### MCP Package
McpServerManager / McpClient / JsonRpcClient / StdioTransport / StreamableHttpTransport / McpSchemaSanitizer / resources/ / mention/ / notifications/

### LLM Clients
- GLMClient：glm-5.1，glm-5v 开头切多模态接口
- DeepSeekClient：deepseek-v4-flash，thinking + tool calls 带回 reasoning_content
- StepClient：step-3.5-flash，可通过 STEP_BASE_URL 切通道
- KimiClient：kimi-k2.6，thinking + tool calls 带回 reasoning_content
- FreeLlmApiClient：auto，默认 http://localhost:5173/v1，OpenAI-compatible 本地网关；可用 `/config provider freellmapi ...` 写入配置后 `/model freellmapi` 切换

---

## .env.example Reference

```bash
GLM_API_KEY=your_api_key_here
# GLM_MODEL=glm-5.1
# GLM_MODEL=glm-5v-turbo
# DEEPSEEK_API_KEY=your_deepseek_api_key_here
# DEEPSEEK_MODEL=deepseek-v4-flash
# STEP_API_KEY=your_step_api_key_here
# STEP_MODEL=step-3.5-flash
# STEP_BASE_URL=https://api.stepfun.com/v1
# KIMI_API_KEY=your_kimi_api_key_here
# MOONSHOT_API_KEY=your_moonshot_api_key_here
# KIMI_MODEL=kimi-k2.6
# KIMI_BASE_URL=https://api.moonshot.ai/v1
# FREELLMAPI_API_KEY=your_freellmapi_unified_key_here
# FREELLMAPI_MODEL=auto
# FREELLMAPI_BASE_URL=http://localhost:5173/v1
EMBEDDING_PROVIDER=ollama
EMBEDDING_MODEL=nomic-embed-text:latest
EMBEDDING_BASE_URL=http://localhost:11434
# EMBEDDING_API_KEY=your_api_key_here
# CAFECLI_LOG_LEVEL=INFO
# CAFECLI_LOG_DIR=/Users/yourname/.cafecli/logs
# CAFECLI_LOG_MAX_HISTORY=7
# CAFECLI_LOG_MAX_FILE_SIZE=10MB
# CAFECLI_LOG_TOTAL_SIZE_CAP=100MB
# CAFECLI_SNAPSHOT_ENABLED=true
# CAFECLI_SNAPSHOT_MAX=50
# CAFECLI_SNAPSHOT_EXCLUDES=.git,.cafecli/snapshots,target,node_modules,dist,.idea,*.class,*.jar
# CAFECLI_SNAPSHOT_DIR=/Users/yourname/.cafecli/snapshots
```

---

## Test Coverage Summary

测试覆盖偏向：解析、计划结构、RAG 核心、Multi-Agent 编排、HITL 策略、策略层拦截、MCP 协议、资源输入层、长上下文策略与 Skill 加载。

不覆盖：真实 LLM 联调、真实 Embedding API、真实 MCP server 联调、终端完整手工体验。

完整测试类列表：CliCommandParserTest / MainBrowserCommandTest / MainInputNormalizationTest / MemoryEntryTest / ConversationMemoryTest / LongTermMemoryTest / MemoryRetrieverTest / MemoryManagerTest / ExplicitMemoryHintsTest / ContextProfileTest / AgentMemoryHintTest / AgentRoleTest / AgentMessageTest / AgentOrchestratorTest / EmbeddingClientTest / SearchResultTest / NetworkPolicyTest / HtmlExtractorTest / WebFetcherTest / SearchProviderFactoryTest / ZhipuSearchProviderTest / VectorStoreTest / CodeChunkerTest / CodeAnalyzerTest / CodeIndexTest / ApprovalPolicyTest / ApprovalResultTest / HitlToolRegistryTest / TerminalHitlHandlerTest / ToolRegistryTest / BrowserSessionTest / BrowserConnectivityCheckTest / SensitivePagePolicyTest / BrowserGuardTest / McpSchemaSanitizerTest / McpConfigLoaderTest / JsonRpcClientTest / McpToolBridgeTest / McpResourceCacheTest / AtMentionParserTest / AtMentionExpanderTest / AtMentionCompleterTest / NotificationRouterTest / PathGuardTest / CommandGuardTest / AuditLogTest / SkillFrontmatterParserTest / SkillRegistryTest / SkillStateStoreTest / SkillBuiltinExtractorTest / SkillContextBufferTest / SkillIndexFormatterTest / LoadSkillToolTest / SkillCommandHandlerTest
