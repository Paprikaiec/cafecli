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

系统属性 > 环境变量 > 默认值：`cafecli.snapshot.enabled`(true) / `cafecli.snapshot.max`(50) / `cafecli.snapshot.excludes`(.git,.cafecli/snapshots,.paicli/snapshots,target,node_modules,dist,.idea,*.class,*.jar) / `cafecli.snapshot.dir`(~/.cafecli/snapshots)

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
- `run --file <issue.md>` 保持同步，无事件循环 / 泵 / push；流与退出码同 v1：答案打到 stdout、退出码 0，v1 的结局文本（`❌ 达到硬轮数上限…`、`❌ 调用 LLM 失败: …`、`⏹️ 已取消当前任务。`）也作为答案这样打；参数错误同 v1 文案、退出码 2。裸启动已接入交互聊天；其他子命令在 stderr 报 `not available in v2 yet`、退出码 2；`serve --http` 在 v2 已移除（同样 stderr、退出码 2）。
- 裸启动由 `app/InteractiveEntry` 装配终端、同一 renderer toolkit、常驻 LineReader 与交互会话。`ui/InteractiveRepl` 的同步订阅者只排队，由单独 UI consumer 转成渲染器的类型化事件；按键只由常驻 readLine 消费，结束时先关闭 / 等待会话、再排空已接收 UI 事件、最后恢复 INT handler 与关闭渲染器。终端读取 / raw-mode 源码守卫已覆盖新入口与 REPL。默认 YOLO（同 v1），Ctrl+Y 与 `/hitl` 经同一会话门面切换 HITL；取消键、审批应答、注册表命令与补全已接入。
  - 各能力注册自己的命令、补全与类型化呈现效果；UI 不按命令名分支，harness 不依赖 JLine。旧解析器委托到中立 `SlashCommandGrammar`，保留别名、大小写、参数个数与分隔符规则；输入先 trim，再分类 / 准备。未知命令只显示旧拒绝文案，不发模型请求；`/plan` 保留原墓碑字节。
  - 普通命令异步占用同一会话执行权，辅助工具仍经 Hook / 审批 / 执行守卫 / 审计，常驻输入框可继续回答审批。已提交的普通输入按 FIFO 等待命令释放，控制命令与审批应答可继续处理；转写 / palette 等模态效果只由输入线程进入。命令释放后的内部唤醒保留草稿、区分真实 INT 与键盘 Ctrl+C，不打断已打开的模态；InputTerminal 复用底层终端的同一个 JLine Status。
  - `/context` / `/policy` / `/hooks` / `/audit` 在 MAIN 运行期间仍可查看捕获的状态；修改主 Agent 的命令继续遵守串行所有权。`/cancel` 保留 v1 的固定空闲提示与行为，运行中的取消走既有 ESC / Ctrl+C 通路。
  - `SourceStreamRenderer` 只解析原始模型 delta 为 reasoning / prose / fenced code；提交块展示输入框实际文本，不取准备呈现或重新读附件。`EventRenderAdapter` 只呈现 MAIN；逐项 ToolEnd 先收集，ToolBatchEnd 若存在则整体替换，IterationEnd 一次发布完整源顺序结果与对应 name / arguments，diff 与 command progress 走各自类型化通道。Retry / Compaction 更新 dock，不安装 headless retry writer。
  - `AgentSession.status()` / `subscribeStatus` 提供不可变 MAIN `SessionStatus`，UI 只排队快照。工厂在 owner 生命周期 / 实际 request 准备处计算纯投影视图、当前缓存 system prompt（含 4-token framing）与共享工具快照的 schema 估算；累计本回合保留的完整 AssistantEntry 用量，ERROR / ABORTED / 子 Agent / 内部压缩不计。价格格式复用 TokenUsageFormatter。状态读取不触发投影或消费 prompt invalidation；初始 / clear 包含动态工具源，构建估算失败仍清理已取得资源。活跃 dock 用累计计数与成本；idle 只保留上下文占用，同 v1 隐去本回合计数 / 成本。状态观察者失败经 failure 订阅报告，不阻断 clear 的旧 Agent 清理，也不覆盖主 / suppressed 异常。
  - renderer 选择从捕获的 ConfigSources 解析：`cafecli.renderer`（回退 paicli）> `CAFECLI_RENDERER`（回退 PAICLI）> inline，继续使用原 fallback 文案与渲染算法，不从 UI 重读宿主进程。离线 screen truth / 自动化伪终端不代表真机验收；后者仍待 ticket29。
- 接入 GLM / DeepSeek / Step / Kimi / FreeLLMAPI 与 fake provider（按脚本回放，格式见 `ai/fake/FakeScript`）。配置加载器一次捕获各家的 Key / model / baseUrl，装配时只为已配置的 provider 建立路由；选择与 alias / fallback / 环境变量优先级保持原规则。DeepSeek 使用固定官方接口；GLM 忽略配置 baseUrl，按每次请求的 glm-5v* 模型选择多模态接口并发送原始 base64，其他模型走 coding 接口。Step 的 reasoning_format=deepseek-style，模型 id 含 2603 时 reasoning_effort=high；Kimi 与 DeepSeek 保留 reasoning_content 历史回传。Step / Kimi / FreeLLMAPI 的 baseUrl 保留原补全 /chat/completions 规则。
- 会话 `/model` 通过同一命令注册表切换主 Agent 的模型数据，不重建 Agent；下一次请求重建 prompt，召回 / 压缩 / Web / trace 使用实际新模型，历史与每 Agent 状态保留，之后 `/clear` 也保留新选择。默认子配置档与显式子配置档保持各自捕获的模型（v1 的默认 spawner 同样捕获启动 client）。成功切换按捕获的 config.json 路径保存 defaultProvider / 显式 model，保留其他字段，不把环境解析得到的 Key 写入文件；保存警告走注入的 warning sink。
  - `/config provider` 显式修改捕获的配置树，之后 `/model` 可选更新后的连接或新配置的 provider；路由按代追加，旧 key 不替换，已有子 Agent 保留原连接 / 凭证与历史。启动配置之外的变更来自这些显式命令，不重新读取宿主环境。
  - 启动时 alias 可回退到别名配置的字段，之后 `/model` 按原命令语法选 canonical 配置；即使 provider / model id 相同，连接设置也可能不同。Model.providerRoute 是显式路由数据，旧六参数构造默认走 provider；装配预建 canonical / 启动 alias 两种连接，子配置档保留启动路由，trace / Web 仍读 canonical provider。上下文摘要复用 ContextProfile 格式，仅省去退役的短期记忆预算字段（PRD 例外 6），不省略 MCP 索引 / prompt cache 信息。
- HTTP 429 / 5xx（500–599）与传输 / 读取异常由 ai 适配器重试，总共最多 3 次请求，等待 250ms / 500ms；没有新增配置旋钮，取消令牌会唤醒等待并取消当前 HTTP 调用。只在尚未向中立流消费者发出内容时重试；已发出的 text / thinking 不重放，尚未公布的工具分片与用量在换次时清空。底层 OkHttp 自动重试仍关闭，core 不增加瞬态重试策略。
  - HTTP 400 中 GLM 的 error.code=1261 与 Kimi 的 invalid_request_error + 特定输入超长消息映射成上下文超限；通用参数错误不触发压缩。DeepSeek / Step / Free 的 context_length_exceeded 嵌套错误码是测试明确命名的兼容假设，来源与边界见 issue22。错误体读取有界，HTTP 正文、Key、SSE 解析异常片段不进入失败 / retry 文案。超限继续走已有的压缩 + 最多一次请求恢复，不计为新的模型 assistant 迭代。
  - headless Composition 拥有 MAIN-only 的 retry 状态 writer：监听器只排队类型化事件，由 writer 在运行中输出 `Retrying model request (attempt 2/3): HTTP 429`，关闭时排空已接受输出并保留中断标记，再打印最终答案。输出 sink 失败在关闭时报告，不提前取消模型工作；子 Agent 的重试不混入主输出。内部压缩请求的 Retry 只留在 ai 流，不公布普通 MAIN 迭代 / retry，也不污染兼容 trace 的 assistant / token 统计。
- 工作区能力复用现有 `read_file` / `write_file` / `edit_file` / `list_dir` / `create_project` / `execute_command` / `glob_files` / `grep_code` 叶子，守卫与文件互斥锁仍在工具执行体内。命令逐行输出经 `ToolUpdate` 实时发布（单条命令也一样），写入的 `ToolEnd.result.details.diff` 带 `path` / `before` / `after`（新文件的 `before` 为 null），原叶子结果的其他字段与文本不变。
  - `/index` 按旧行为切换 MAIN 的工作区 / 路径守卫、项目记忆、LSP、语义工具与默认快照；已有子 Agent 仍绑定原项目，之后创建的子 Agent 使用新项目。切换前先排空旧快照服务已接受的 post-turn 写入。命令 `/search` / `/graph`、路径补全与相对快照目录仍按启动目录解析；自定义快照服务不替换。RAG 存储目录由配置加载器捕获，经显式叶子重载传入，不在检索 / 索引时重读属性或用户 home。
- 权限能力已接入 `beforeToolCall`：需审批的工具按模型原顺序全部问完，再开始执行；`Approver` 是可取消的阻塞接口，同一会话跨 Agent 串行请求。会话内支持按工具 / MCP server 全部放行，关闭审批时清空两类记忆并使先前审批的记忆授权失效；关闭期间或重新开启后才到达的旧答复仅批准本次调用。批准修改参数后，执行 / 事件 / 完成回调 / 审计使用新参数，assistant 历史保留模型的原始参数；守卫始终在叶子里执行。当前真实 `run --file` 同 v1 无人值守路径一样关闭审批、自动执行，不读 stdin；交互审批经常驻输入框应答，不能调用渲染器的同步读键接口。
- 交互 `InteractiveApprover` 只保存待答复的 rendezvous，不保存工具 / server 放行集合；全部授权仍由同一个 PermissionCapability 管。卡片与通知经 UI 队列，按源顺序逐条展示，整批答完才执行任何工具。`y` / `n [原因]` / `a [server|s]` / `s` / `m <JSON>` 沿用 v1；空白 / 非法回答只重显当前卡，敏感操作不能整体批准，修改参数仍进入叶子守卫。审批答复不追加为用户输入；后台卡保留真实 task 调用者。render.ApprovalRequest / ApprovalResult 及中立 ToolApprovalPolicy 收走最后的 v1 HITL 呈现类型，旧 API 留委托适配。
  - cancel 经同一门面覆盖完整准入，待决审批由取消令牌释放为拒绝；ESC / Ctrl+C 保留运行中草稿，未落地转向丢弃，Attached 随主回合取消，Detached 保留。Ctrl+D 运行中无操作，空闲空框退出；空闲 Ctrl+C 清非空框、空框约 2 秒内再次按下退出。浏览层 ESC 只关层、Ctrl+C 停回合但不关层；审批到达关闭浏览层。运行中的模型请求可立即取消（PRD 例外9），屏幕保留已流出的部分文字后显示取消结局。信号通路包住 RuntimeException / Error，只排队反馈，不让 JVM handler 漏异常直写终端。
  - UI consumer 用 CafeCliLineReader.tryRefreshInputBox 非阻塞采集刷新；不能等输入线程持有的模态锁，否则它身后的审批通知无法关闭该模态。该入口与原 blocking refresh 共用 redisplay，原 v1 路径、帧 / lease / 擦屏算法不变；模态返回后的 widget REDISPLAY 保留。
  - 项目 Hook 信任按需打开同一个终端 / renderer / captured reader，在常驻 REPL 前展示真实命令并同步询问；不用第二个 raw reader，也不等待尚未启动的 UI consumer。provider 验证在首次打开终端前。批准钉住同一 SHA-256 信任存储，改字节后重问，显式拒绝 / headless 跳过项目项；Ctrl+C / EOF 则像 v1 一样向上传播、中断本次整批 shell Hook 加载（用户级也不安装），不能改当普通 n 或记成 declined。信任确认后把同一 reader 生命周期交给 REPL，最终恢复 INT handler 并关闭。
- Hook 能力复用现有分发器与租户，开关仍默认全关（属性见下文 Lifecycle Hooks）。PreToolUse 在审批前按 BLOCK > ASK > ALLOW > CONTINUE 折叠：BLOCK 回灌类型化 `BLOCKED`；交互策略下 ASK 强制本次审批（即使审批关闭或已有记忆授权）、ALLOW 只跳过审批，守卫仍在执行时运行。无人值守同 v1 基础注册表只处理 BLOCK，忽略 ASK / ALLOW，也不记 hook-allow 审计。PostToolUse 只在工具体正常返回后分发（包含业务级 ERROR，不包含抛异常 / 守卫拒绝 / 未知调用），上下文经边界收件箱进入下一次请求。Pre/Post payload 使用会话唯一且跨回合稳定的 `session-UUID`；UPS / Stop 同 v1 不带 sessionId。台账 / Stop 分发器按 Agent 创建；台账跨回合保留，Finish Gate 的工具摘要与阻断计数每回合重置，摘要按模型工具顺序折叠。UserPromptSubmit 在核心日志与模型请求前运行；交互会话的普通输入与 busy 转向输入都依次经过 MCP mention → 本地 `@path` → UPS，准备在会话锁外只运行一次，再原子决定排队或转向；阻断以 `SubmitResult.Blocked` 返回，headless 保留 v1 stderr 文案与退出码 1。四类 shell hook 均接收显式回合取消令牌。
- 审计能力在同步监听器里只排入不可变事件，由会话拥有的单写线程维护调用状态、构造 / 脱敏条目并落盘；`flush()` 等待已排入的记录，`AgentSession.close()` 按注册逆序关闭资源并排空审计，`run --file` 用 try-with-resources 保证退出前完成。会话构造失败也清理已注册的资源，某项清理失败仍继续关闭其余项。审计语义保持不变：拒绝 / 跳过记 `hitl` deny，抛出的策略拒绝记 `policy` deny，工具体正常返回仍记 allow（包括业务级 ERROR），抛异常或已注册工具的参数 JSON 解析失败记 error；未知工具不记执行审计。结果的结构化 `execution` 字段保留实际执行出处，审计不从状态或文本前缀反推。
- 长期记忆能力复用 `LongTermMemory` / 检索叶子与原 `save_memory` 工具；召回在回合开始时作为 `MemoryRecall` 条目追加在用户输入后，按实际 Agent 模型窗口派生预算，不改 system prompt，也不创建短期记忆。写入仍只来自 `/save`、用户明确要求的 `save_memory` 或原有显式浏览器登录偏好提示；提取接口只定义时机，未接入自动提取实现。项目作用域使用真实路径，符号链接入口与原目录共享项目身份。`/memory list` / delete / clear 同 v1 管整个存储，search 按当前项目可见性过滤。
- 会话命令由能力注册，经 `AgentSession.dispatch` 在空闲时调用（不进入 Agent 日志 / 模型请求），命令清单供后续 UI 补全使用。`SessionCapability` 提供 `/clear`：重建主 Agent、清空日志与待续输入、重置 Agent 级状态和审批记忆，保留长期记忆与会话资源。system prompt 在创建时组装并缓存；显式重建请求合并到下次模型请求前生效，普通回合不会重读模板。MCP / Skill 集合变化已使用该重建口，模型切换在后续票接入。Agent 级资源在 `/clear` 后关闭旧 Agent 时释放；关闭会话先取消并等待回合，再关主 Agent，最后按逆序关闭会话资源。构造失败会清理部分资源，清理失败仍尝试其余项并保留 suppressed 异常。
- MCP 能力按会话复用原 `McpServerManager` / 客户端 / 通知路由，所有已发现工具仍立即暴露为 `mcp__server__tool`。每次请求同时固定工具定义与可执行适配器，`list_changed` 在下次请求生效；资源索引进入缓存 system prompt，重复通知合并重建。每个 Agent 独立复用原单条 25k / 同批 125k 字符预算及最大优先落盘算法，路径仍是 `tool-results/session-UUID/main|task_N/callId`；`/clear` 清旧 main scope，Agent 关闭禁止迟到写入，会话关闭清整个 session 目录。未投入执行的替换 Agent 不拥有旧 scope 文件，构造 / clear 失败不会误删旧主 Agent 的结果。MCP mentions 在输入 UPS 前展开；交互输入还在两者之间展开本地 `@path`，旧 CLI 包只保留中立 `input/LocalPathMentionExpander` 的委托。`/mcp` 的状态 / restart / logs / disable / enable / resources / prompts 已注册。项目 `mcp.json` 仍不过信任闸。mention 的资源读取审计也只排队，关闭前排空；虚拟资源工具保留原叶子的文本返回语义。
- Skill 能力复用原三层发现（builtin → user → project）、启用状态文件、索引格式与 `load_skill` 叶子；5KB 截断、最多 3 项 LRU、工具成功 / 错误文案不变。每个 Agent 的 buffer 独立，`/clear` 丢弃待注入正文。正文只在下一个真实用户回合的输入准备期 drain 为独立原始 `SkillBody`，在源用户条目后追加；没有图片准备时，纯转换将其前置到对应的真实 UserEntry，拼成 v1 的单条用户消息字节。图片准备已合并的前缀按记录的数量跳过，不重复注入；召回 / 转向等额外 user 呈现不能抢走正文，源用户输入和正文条目均不改写。`/skill` 命令复用共享 `skill/SkillCommands`，v1 CLI 只保留委托适配；索引缓存按每个 Agent 实际索引过的不可变集合比较；外部 skills.json 改动经 reload 可更新旧缓存，新子 Agent 的索引不会掩盖旧主 Agent 的变化，未变的 reload 不重建；内置解压失败仍按原文案经显式状态出口报告并继续扫描 user / project。
- Images 在前缀贡献之后、源 UserEntry 追加之前，复用 ImageReferenceParser 对「Skill 前缀 + 用户原文」只解析一次；包括图片唯一输入的默认文案、前缀内引用、清洗 / disclaimer / 附件顺序，都沿用 v1。详细解析结果用同一轮成功引用的起始 offset 区分来源：源用户消息保留实际原文和用户来源的图片，原始 SkillBody 独立保留，UserEntry 的可选 presentation 缓存精确组合结果（含前缀图片），preparedPrefixCount 只覆盖已合并的准备期前缀。旧构造不带缓存，旧 JSON 可读；缓存与来源都可序列化，不在纯转换 / 投影里重读文件或剪贴板。其余回合开始写入者仍在用户追加后运行，并接收原始输入。历史图片投影只改返回视图里的来源与缓存，保留当前真实用户回合的图片，所有更早图片替换成 v1 精确占位文本；源日志原字节不变。工具图片从请求的 tool_result 里分离，在整批结果之后按源顺序各呈现为带原引导语的 user 消息；源工具结果仍保留原图片。
- LSP 能力共享原解析器、按 Agent fork 独立 pending 状态。整批工具完成后，只对真实正常返回、类型化 OK 且带结构化 diff.path 的 write_file / edit_file，按唯一最终路径复用原诊断；读取前重过 PathGuard，避免同批其他调用将路径换成逃逸链接。结果用 `LspDiagnostics` 经边界收件箱在紧接的下一次迭代呈现，关闭 / clear 丢弃旧状态；不靠结果文本嗅成功，不为审批 / Hook 提前供应的假 diff 运行诊断。混合 LSP / Hook / 转向的旧版呈现顺序仍由 ticket27 的组合契约验收负责。
- Web / RAG / Browser 分别按能力闭合：复用原 web_search / web_fetch、search_code、browser_connect / browser_disconnect / browser_status 叶子；未挂能力的 Agent 不提供对应工具。freshness 复用原触发词、离线排除、查询清洗与精确正文，作为回合开始的 Freshness 条目写在用户后，不改缓存 system prompt；Hooks 先于 Web 挂载。StepSearch 的嵌套调用与 freshness、浏览器 /tabs 都走辅助调用口：同一固定工具快照、Hook / 审批 / 执行守卫 / Post / 审计；CANCELLED / BLOCKED 仍按类型化状态原样回给外层，不触发真实搜索回退。辅助调用不另发普通工具事件或模型结果，不混入 Stop 的模型工具摘要；嵌套叶子在原 worker 执行，避免四路父调用占满同一个池后等待子调用。
- Browser 检查装饰完整工具快照（含动态 MCP）；拒绝留在执行体内，Hook ALLOW 也不能绕过。敏感页的强制单次审批只在普通启用审批路由上生效，即使已有记忆授权也再问；Hook ALLOW / 关闭 HITL 仍沿用 v1 跳过 preview 的行为。BrowserAuditMetadata 随类型化执行结果进入拥有的审计写入者；导航后状态只接受真实正常返回的执行出处。/browser 命令复用 browser/BrowserCommands 叶子，主入口保留委托适配；/tabs 使用辅助工具路线，连接模式切换清除 chrome-devtools 的 server 授权。
- 空闲命令在 session 锁内预留准入、owner 与取消令牌，handler 在锁外执行；cancel / close / awaitIdle 覆盖此预留，命令期间并发提交不能当成向空闲 Agent 的转向。只有当前命令 owner 能调用命令工具或在预留期间 /clear。辅助调用口随 writer / 命令或父工具执行结束而关闭，取消 / 关闭拒绝迟到调用；已有管理器 restart I/O 仍保持原来的有界行为，不承诺令牌能立即中断每个管理操作。
- 前台子 Agent 能力复用 TaskTools 的 spawn_agent 定义 / 参数验证 / 文案，经中立 TaskSpawner 口执行；旧 AgentSpawner 接口保留兼容继承。Composition 显式构造同一个 AgentFactory 注入会话与派生能力：先在会话 Hook 前认领所有权，防止复用失败误关原会话资源，再在 Hook 后绑定能力 / 事件快照。默认子配置不挂派生能力，其他能力按配置档选择；每个子 Agent 有自己的日志、预算、Skill / LSP / Hook 状态与 MCP 结果 scope，事件以 task_N 标记。ai.RoutingProvider 按配置档 Model.provider 选择已注入适配器，未知 provider 返回类型化 FATAL；其他适配器与 /model 仍在后续票。子回合在独立虚拟线程运行、用单向挂接父令牌的独立取消令牌；runStart 生命周期先于普通写入者，runEnd 在 finally 中关闭派生准入、取消 / 等待已准入子执行并释放 Agent 级资源后，才释放父回合准入，覆盖超时弃置 worker 与构造竞态。清理不在事件监听器阻塞，并保留原异常及 suppressed 清理错误。父请求只收到 result-only 结果，旧任务标签 / 失败与预算文案和真实类型化结果分开，工具体正常返回仍为 OK；子 freshness 状态不写主 stdout。任务生命周期按下一段的会话所有权保留；headless 的 background=true 仍沿用 v1 同步退化。
- 工具 `ToolEnd` 仍在单项执行完成时立即发布；整批预算可能随后替换结果内容，变化时另发 `ToolBatchEnd`（最终源顺序结果），再原子写入 assistant / results。基数、call id、工具名、状态与 `isError` 不变，执行出处也保留。审计消费立即执行事件；Trace / UI 必须先收集完成结果、按最终批次替换，再在 IterationEnd 发布，避免展示未压缩大结果或重复记录。
- 上下文能力在迭代边界按实际 Agent 模型窗口的 90% 检查已投影消息与缓存 system prompt 的估算大小；压缩写入者保留最近 3 个实际用户回合，将较早范围的摘要作为 `Summary` 追加到源日志，原条目不删。投影先按源范围归位 / 替换摘要，再按注册顺序做普通视图变换；两阶段各自保留注册顺序，避免隐藏条目等过滤操作移动摘要范围。重复摘要覆盖旧摘要，记忆提示也读同一投影时序。成功压缩发类型化 `Compaction` 事件。overflow 使用同一个写入者，有实际恢复条目才重试一次；第二次 overflow 仍是类型化失败。无可压缩范围、空 / 失败 / 取消的摘要不声明覆盖、不做无效重试；订阅者异常仍向调用方传播。`/clear` 重建后不保留旧摘要范围，自动记忆提取仍未启用。
- 运行预算属于 Harness，核心只保留硬迭代上限。停滞默认连续 3 次同名、同原始参数且同源顺序的工具批次（忽略 call id），优先于 token 预算；Stop 要求继续的纯文本迭代不清掉工具签名窗口。token 硬限默认 `Integer.MAX_VALUE`，模型窗口的 80% 只是旧策略的显示提示；仅显式属性启用硬限。预算在普通迭代结束参与者之后、确定需继续时检查，保留它们的追加条目与整批工具结果；自然最终答案即使跨过 token 阈值也正常完成，同 v1。两类状态每个 Agent 独立、每回合重置。
- 配置（`harness/config/ConfigLoader`，来源与优先级同 v1，新旧名回退走 `ConfigHome`）：
  - provider：`-Dcafecli.provider`（回退 `-Dpaicli.provider`）> `CAFECLI_PROVIDER`（回退 `PAICLI_PROVIDER`）> 用户级 `config.json` 的 `defaultProvider` > `glm`。前两项是 v2 新增，空白值视为未设。随后沿用 v1 的别名归一化与按 Key 回退：先试选中的 provider，没有可用 Key / 未知 provider 时按 glm → deepseek → step → kimi → freellmapi 找第一个有 Key 的；选中了有 Key 但尚未移植的 provider 仍报 not available。
  - fake 脚本：`-Dcafecli.fake.script`（回退 `-Dpaicli.fake.script`）> `CAFECLI_FAKE_SCRIPT`（回退 `PAICLI_FAKE_SCRIPT`），相对路径按工作目录解析。v2 新增。
  - 审计目录：`cafecli.audit.dir`（回退 `paicli.audit.dir`）> `CAFECLI_AUDIT_DIR`（回退 `PAICLI_AUDIT_DIR`）> 用户配置目录下 `audit/`；相对目录按工作目录解析，保留 v1 的脱敏与字段截断。
  - Hook：事件 / 租户开关与 Finish Gate 参数在加载时固定到 `HooksConfig`，系统属性支持 `paicli.*` 回退。`hooks.json` 仍按用户级再项目级加载，项目配置保留 SHA-256 指纹信任与确认端口，headless 跳过未信任项。命令变量显式按环境 > 系统属性 > 项目 `.env` > 用户 `.env` 展开，`${HOME}` / `${PROJECT_DIR}` 来自加载输入。信任 / 阻断 / 免审批观察只排队到会话拥有的审计写入者，关闭会话时排空。
  - MCP：`McpConfig` 固定项目根、用户 / 项目配置目录、属性 / 环境 / home 插值输入，复用原配置优先级与 StepSearch 自动配置；不读真实宿主环境来补显式 fixture 的空配置。叶子自身原有 timeout / jq 探测仍由叶子负责。
  - Web：SEARCH_PROVIDER / GLM_API_KEY / ZHIPU_SEARCH_ENGINE / SERPAPI_KEY / SEARXNG_URL 按同名环境 > 系统属性 > 项目 .env > 用户 .env 取值，固定到 WebConfig，再调用原 SearchProviderFactory 的显式输入重载。RAG：EMBEDDING_PROVIDER / MODEL / BASE_URL / API_KEY 按同名环境 > 系统属性读取，不加 .env 来源；默认 ollama / nomic-embed-text:latest / localhost:11434，zhipu / glm 的默认 URL 沿用原叶子。能力只接收各自切片；旧叶子构造仍保留原动态读取。
  - Skill：目录来自已固定的 ConfigHomes：用户级 skills-cache / skills / skills.json 与项目级 skills；没有新增环境旋钮。LSP：`cafecli.lsp.enabled` / `cafecli.lsp.max.diagnostics`（回退 paicli 属性）优先对应 CAFECLI / PAICLI 环境变量，默认 true / 20，非正 / 非数字上限回退 20。v2 走显式叶子构造 / formatter 重载；旧构造仍保留动态属性 / 环境读取。
  - 长期记忆目录：`cafecli.memory.dir`（回退 `paicli.memory.dir`）> `CAFECLI_MEMORY_DIR`（回退 `PAICLI_MEMORY_DIR`）> 用户配置目录下 `memory/`；相对路径按工作目录解析，沿用 `long_term_memory.json` 格式。能力只接收 `MemoryConfig` 切片。
  - 硬迭代上限：同 v1 的 `cafecli.react.hard.max.iterations`（只认系统属性，默认 50）。
  - 运行预算：`cafecli.react.token.budget` / `cafecli.react.stagnation.window`（回退 `paicli.*`），默认 `Integer.MAX_VALUE` / 3；不新增环境变量旋钮。非正 / 非数字值回退默认，停滞窗口 1 同 v1 为非法配置；`BudgetConfig` 只传预算能力。
  - 禁用编辑：`cafecli.edit_file.disabled`（回退 `paicli.edit_file.disabled`）在加载时固定进类型化配置；去空白后 `true` / `1` / `yes`（不区分大小写）禁用，其他值不禁用。禁用后既不提供定义，也不能执行该工具。命令超时仍为构造输入（默认 60 秒，无新增配置键）；批次超时由工厂传入 `max(命令超时 + 5, 90)` 秒，仅多调用批次使用，单调用由叶子自己的超时控制。
- `run --file ... --trace-file <path>` 写兼容 JSONL；相对路径按捕获的工作目录解析。TraceCapability 的 session 监听器只排队 MAIN 时序 / 用量，runEnd 由 owner 捕获不可变的最终呈现消息、已缓存 prompt 和模型，再由会话拥有的 writer 关闭时写盘；不序列化原始 agent log、不在监听器投影 / I/O，也不重复读取图片。system / user / assistant / tool_result / result 字段沿用 v1，图片 text parts 的双换行与 `[已附加 N 张图片]` 后缀、image_parts 和最终归一化工具批次均保留。子 Agent / 内部压缩调用不增加主 trace 的 assistant 数或 token；如以后加更细事件，只能用新 type。读取 cachedSystemPrompt 不消费 prompt invalidation，避免 trace 重建一个模型没收到的未来 prompt。
  - result.tokens 逐回合重置，只累加已完整完成的 MAIN 响应；ERROR / ABORTED 部分响应不计。result.turns 按最终呈现历史中的 assistant 数计。PRD parity 例外 12 单独记录保留最终 reasoning、修正 v1 非自然退出时 zero / stale 用量的差别；字段本身不变。CLI 在关闭 / 排空 writer 后才打印答案；写 trace 失败不提前取消模型工作，按 v1 走 stderr / 退出 1 且不打印答案。无可追踪回合（文件读失败、UPS BLOCK）不打开 / 截断原 trace。日志文件不是会话恢复源。
  - 会话关闭即使被前序资源中断，也须等待已接受的 trace 观察和实际文件写入排空，再保留中断标记返回；不能把中断当作丢弃 JSONL 尾部的许可。真实 writer 的阻塞 / 释放与最终文件字节由 session seam 测试钉住。
- v2 快照能力复用同一 SnapshotService / SideGitManager：只为主 Agent 的每个回合同步锚 pre-turn，发生在普通 run-start 写入者（含 freshness 辅助工具）之前；子 Agent 共享 revert_turn，但不单独锚回合。post-turn 在 Attached 子执行清理后排队。Composition 把共享服务的 session ownership 放在任务 / MCP 资源前、agent lifecycle 放在任务后，最终先停所有使用者再 awaitIdle / close，保留原异常、suppressed 清理错误和中断标记。输入感知 runStart 重载仍是通用 SPI，旧 Consumer 调用兼容。/snapshot（list / status / clean）与 /restore 走原命令文案和 offset 规则，不增加模型历史；工具定义集中于 SnapshotTools，旧 SessionTools 委托，正常返回的业务 ERROR 仍有 RETURNED 执行出处。启动检测到被劫持的 gitfile 就停用快照并报告原修复指引；headless 只写 stderr，不改 gitfile。PRD parity 例外 11 记录 v1 headless 没有锚点而 ticket19 明确要求主回合锚定的差别。
  - `snapshot.enabled` / `dir` / `max` / `excludes` 经配置加载器一次捕获：cafecli 属性（paicli 回退）> CAFECLI 环境（PAICLI 回退）> 原默认。已存在的空属性仍遮住环境值，随后走原解析默认；max 仍 clamp 至 ≥1。默认目录来自捕获的 ConfigHomes，相对 dir 按项目工作目录绑定。SnapshotConfig.fromValues 与 fromEnvironment 共用解析 / 默认排除项，不重复维护列表；仓库创建、恢复围栏 / file mode 与历史 home 检测仍由原叶子实现。
- v2 任务由会话拥有：运行结束且 run-finally 完成后才转空闲，保留 Agent 日志与能力资源供 `send_message` 续派；LRU 回收 / 会话关闭才释放结果 scope 与 Agent。`task_output` 用同一个工厂的纯投影呈现、按消息镜像上限截尾，不复制 Skill / 图片 / 摘要的呈现规则；续派运行中由 owner 在 runStart / 迭代边界 / overflow / iterationEnd 发布不可变投影视图，不并发读取活日志，也不在事件监听器做投影。首派尚未完成仍返回原过期文案；登记表只留有界结果摘要。Attached 随等待中的父回合取消，显式后台 / auto-background 后的 Detached 只能单独 `/task cancel` 或会话关闭；关闭会话要停并等待全部任务，任何回调失败都不能跳过其余清理。替换主 Agent 时保持已安装的就绪收件箱，成功后迁移尚未落地的任务通知，失败替换保留旧目标。`/task` 注册为独立准入的 control 命令，可在主回合运行中检视 / 取消；close / awaitIdle 等待该准入释放，工具命令仍走原 idle-owner 令牌，control 不能借用它或清空主 Agent。
  - `TaskConfig` 只在配置加载器读取 `cafecli.task.auto-background-ms` / `max-concurrency` / `max-depth` / `max-idle` / `message-mirror-cap`（各键保留 `paicli.*` 回退），默认 120000ms / 8 / 5 / 8 / 50；不新增环境变量旋钮。auto 阈值 ≤0 关闭，解析沿用 Long.decode / Integer.decode；缺失 / 非数字回退默认，四个限制的已解析非正值仍拒绝配置，保留 v1 行为。模式显式构造：headless Composition 保持同步；interactive Composition 打开会话串行执行器与 auto-background，并由 UI 入口装配终端。三个独立准入 / LRU / 镜像策略抽到中立 `task` 叶子，v1 原位置只留兼容构造 / fromProperties；v2 不借用旧 Agent spine。busy 任务完成在下一个可接收的迭代边界呈现（用户于 2026-10-23 批准 PRD parity 例外 10，v1 则等当前回合结束后另起回合）；错过最后边界的通知由会话收集为自治回合，headless 不引入 push。
- `AgentSession.builder().interactive(true)` 显式启用串行虚拟线程 owner；headless 不创建该执行器。交互 submit 返回 `Queued` / `SteeringAccepted` / `Blocked`，activity 与 `SessionCompletion` 订阅者只排队，完成通知在 Agent 生命周期清理之后发布。普通命令由同一 owner 执行、调用方阻塞等待；`/task` control 保留独立准入。cancel / close / awaitIdle 覆盖准备、排队回合与两种命令，关闭丢弃尚未开始的工作并围住迟到生产者。准备结束为 BLOCK / cancel / 异常时仍转交已收集任务通知，不能把保留通知误报为空闲。
  - 回合边界先按原字节分组任务 XML、再拼实际用户输入；多个错过最终迭代的通知只触发一个自治回合。通用 `RunInput` seed 在一次 core 准入内保留原准备顺序；`RunInputEntry` 分开保存任务来源、实际用户、一次性准备呈现与 Skill 元数据，混合输入的源 UserEntry 不伪造任务文本。自治回合不造 UserEntry，仍调用 MAIN 生命周期 / 快照，并显式推进历史图片 cutoff；纯投影不重读文件。
- 真实 provider 的 `apiKey` / `model` / `baseUrl` 读取顺序同 v1：用户级 `config.json` 的 `providers.<name>` 非空字段 > 对应环境变量 > 工作目录 `.env` > 用户主目录 `.env`；`.env` 不做引号剥离或 shell 展开。Kimi 兼容 `MOONSHOT_*`（在 `KIMI_*` 环境与 `.env` 都无值后才读）。项目级 `config.json` 不参与此配置。
- DeepSeek 默认 `deepseek-v4-flash`、固定请求 `https://api.deepseek.com/chat/completions`；仍不使用配置里的 `baseUrl` / `temperature` / `maxTokens`，沿用 v1 实际 wire 行为。HTTP 超时读取现有 `-Dcafecli.llm.{connect,read,write,call}.timeout.seconds`（旧前缀回退），默认 60 / 300 / 60 / 600 秒。取消会中止等待响应头或流式响应体的 HTTP 请求。
- `ai/ModelRegistry` 内置五家默认模型的元数据；用户级 `config.json` 可加 `models` 数组覆盖或添加记录，例如 `{"models":[{"provider":"deepseek","id":"deepseek-v4-flash","contextWindow":500000,"maxOutputTokens":8192}]}`。省略字段继承该 provider 的默认值；`features` 若填写则整体替换，取 `REASONING` / `IMAGE_INPUT` / `PROMPT_CACHING` / `TOOL_CALL_STREAMING`。未列出的模型 id 沿用该 provider 的默认元数据，保持 v1 可任意指定模型 id 的行为。元数据上限不额外变成请求参数。五家真实适配器均已接入，元数据与连接路由分开捕获。
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
12 个核心内置工具（`edit_file` 可被 `cafecli.edit_file.disabled` 关闭）+ 记忆与 Skill 2 个（`save_memory` / `load_skill`）+ 浏览器 3 个（`browser_connect` / `browser_disconnect` / `browser_status`）+ 异步任务 3 个（`spawn_agent` / `send_message` / `task_output`）+ MCP 动态工具 / executeTools() 并行入口。工具定义与调用的数据类型（`Tool` / `ToolParam` / `ToolExecutor` / `ToolInvocation` / `ToolExecutionResult`）与有副作用工具清单 `MutatingTools` 是同包的独立叶子类型，不嵌在 registry 里；`McpServerManager` 经 `mcp/McpToolSink` 回写 MCP 工具，registry 实现该接口。代码理解默认路径是 `glob_files` / `grep_code` / `read_file` 现用现查；`read_file` 裸读大文件默认超过 256KB 会报错并提示改用 `offset`/`limit`、`grep_code` 或 `jq` 精确检索，阈值可用 `cafecli.read_file.max_unranged_bytes` / `CAFECLI_READ_FILE_MAX_UNRANGED_BYTES` 调整。`grep_code` 优先走 ripgrep 并按 `max_results` / `head_limit` / `max_chars` 渐进返回，`search_code` 保留为 RAG 语义辅助。确定性搜索链路的回归样例见 `docs/code-search-golden-set.md`。`execute_command` 的实现在 `CommandTool`，`glob_files` / `grep_code` / `search_code` 在 `SearchTools`，`read_file` / `write_file` / `edit_file` / `list_dir` / `create_project` 在 `FileTools`（`edit_file` 的纯计算内核在同包的 `EditFileTool`），`web_search` / `web_fetch` 在 `WebTools`，`browser_connect` / `browser_disconnect` / `browser_status` 在 `BrowserTools`，`save_memory` / `load_skill` 在 `SessionTools`，`revert_turn` 在 `SnapshotTools`（SessionTools 保留委托），`spawn_agent` / `send_message` / `task_output` 在 `TaskTools`。前三组经 `ToolEnv`（一组 supplier）在**每次调用时**现取项目根 / 路径围栏 / 命令输出观察者 / 文件写入观察者 / LSP 管理器——子任务 registry 与 `setProjectPath` 都在注册之后才换掉这些状态，注册期抓值会把围栏落在 JVM 工作目录上；Web / Browser / Session / Snapshot / Task 工具只有自己用到的协作者（web 服务与模型选择、浏览器连接器、记忆写入器、Skill 索引与缓冲、快照服务、`AgentSpawner`）不进 `ToolEnv`，以 supplier 传给各自的构造器，同样调用时现取（均由 `ToolEnvWiringTest` 钉住）。

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
# CAFECLI_SNAPSHOT_EXCLUDES=.git,.cafecli/snapshots,.paicli/snapshots,target,node_modules,dist,.idea,*.class,*.jar
# CAFECLI_SNAPSHOT_DIR=/Users/yourname/.cafecli/snapshots
```

---

## Test Coverage Summary

测试覆盖偏向：解析、计划结构、RAG 核心、Multi-Agent 编排、HITL 策略、策略层拦截、MCP 协议、资源输入层、长上下文策略与 Skill 加载。

不覆盖：真实 LLM 联调、真实 Embedding API、真实 MCP server 联调、终端完整手工体验。

完整测试类列表：CliCommandParserTest / MainBrowserCommandTest / MainInputNormalizationTest / MemoryEntryTest / ConversationMemoryTest / LongTermMemoryTest / MemoryRetrieverTest / MemoryManagerTest / ExplicitMemoryHintsTest / ContextProfileTest / AgentMemoryHintTest / AgentRoleTest / AgentMessageTest / AgentOrchestratorTest / EmbeddingClientTest / SearchResultTest / NetworkPolicyTest / HtmlExtractorTest / WebFetcherTest / SearchProviderFactoryTest / ZhipuSearchProviderTest / VectorStoreTest / CodeChunkerTest / CodeAnalyzerTest / CodeIndexTest / ApprovalPolicyTest / ApprovalResultTest / HitlToolRegistryTest / TerminalHitlHandlerTest / ToolRegistryTest / BrowserSessionTest / BrowserConnectivityCheckTest / SensitivePagePolicyTest / BrowserGuardTest / McpSchemaSanitizerTest / McpConfigLoaderTest / JsonRpcClientTest / McpToolBridgeTest / McpResourceCacheTest / AtMentionParserTest / AtMentionExpanderTest / AtMentionCompleterTest / NotificationRouterTest / PathGuardTest / CommandGuardTest / AuditLogTest / SkillFrontmatterParserTest / SkillRegistryTest / SkillStateStoreTest / SkillBuiltinExtractorTest / SkillContextBufferTest / SkillIndexFormatterTest / LoadSkillToolTest / SkillCommandHandlerTest
