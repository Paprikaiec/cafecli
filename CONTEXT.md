# PaiCLI

Java 实现的终端 AI 编程 Agent（ReAct 循环 + 工具调用），对标 Claude Code / pi agent 的核心能力。

## Language

### 工具执行

**文件互斥锁（File Mutation Lock）**:
对工作区文件做写入的工具必须持有的按文件粒度的互斥保证：同一文件的变更串行排队，不同文件照常并行。是文件写入并发安全的唯一正典机制。
_Avoid_: 读写分批、写串行、read/write partition（已废弃的全局串行方案）

**工具批次（Tool Batch）**:
同一条 assistant 消息里返回的一组 tool call，默认并行执行（上限 4 线程），结果按源顺序回灌对话历史。批内兄弟调用互相看不到对方的结果。

**编辑事务（Edit Transaction）**:
单个 `edit_file` 调用内的一组 `edits[]` 精确替换，作为一个原子单元生效——全部成功或整体不变（全有或全无）：任一处匹配失败、歧义或区间相交都使整批作废、文件不动。与并行、互不可见的「工具批次」相对——编辑事务是一个 tool call 内的原子批，不是多个 tool call。
_Avoid_: 编辑批次、批量编辑（与「工具批次」混淆）；MultiEdit（英文别名可，中文统一用编辑事务）

### 生命周期 Hook 与策略层

**生命周期 Hook（Agent Lifecycle Hook）**:
ReAct 主循环在固定生命周期点（`PreToolUse` / `PostToolUse` / `Stop` / `UserPromptSubmit`）触发的可叠加扩展点，经统一分发；每个[[Hook 租户]]给出一个**模型反馈级**决定：`CONTINUE`、`BLOCK`（附原因回灌模型并续跑）、`ALLOW`（仅免除 HITL 审批）、`ASK`（强制 HITL 审批）。事件分类与 shell 契约（exit 0/2 + JSON 超集）对齐 Claude Code 公开 hook 模型，但双向不越过[[策略守卫]]：BLOCK 是反馈不是安全拒绝，ALLOW 也无法豁免守卫拒绝。
_Avoid_: 拦截器/interceptor（那是权限层）；回调/callback（太泛）；守卫/guard（守卫做安全拒绝，hook 做反馈与审批意见）

**策略守卫（Guard）**:
工具执行**前**的策略**拒绝**层——`PathGuard`（路径限定在项目根内）、`CommandGuard`（命令黑名单辅助防线），与 HITL 审批同属权限链（拦截顺序：HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard）。守卫拒绝是**绝对**的：用户无法批准，[[生命周期 Hook]]的 ALLOW 也无法豁免（有意偏离 Claude Code——其 hook allow 绕过更多权限层）。safe-edit（禁止编辑测试目录 `src/test/**`）即一条 Guard 规则，而非 hook。
_Avoid_: hook/钩子（hook 不做安全拒绝）；过滤器/filter

**Hook 租户（Hook Tenant）**:
挂在统一分发上的一个具体 hook 实现。内建租户（进程内、可访问会话状态）与用户配置的外部命令租户（shell 命令，stdin JSON + exit code 契约）同权、共用同一决策契约。
_Avoid_: 内置规则（不表达同契约）；插件/plugin（无加载隔离语义）

**项目 Hook 信任（Project Hook Trust）**:
项目级 hook 配置默认不受信：首次加载须用户确认，确认按配置内容指纹钉住，内容变更即重新确认；内建与用户级配置默认受信。hook 命令本身不过 CommandGuard——守卫防的是模型，hook 是用户签署的配置。
_Avoid_: 白名单（不表达"变更即失效"）

**读前写台账（Read-before-Write Ledger）**:
按 Agent 作用域记录「哪些文件在何种状态下被读过」的台账；写类工具执行前据此阻断盲写（未读先写）与陈旧写（读后文件已变），成功写入视同刷新。是 `PreToolUse` 的首个内建租户。
_Avoid_: 读缓存（台账不存内容）

**收尾门（Finish Gate）**:
`Stop` 点的内建租户：本回合有写入、且最后一次写入后没有任何验证命令成功执行时，阻断收尾并把「先自证再交付」回灌模型续跑，有限次阻断防循环。验证指 agent 在工作区内自行运行的测试/构建，永不引用任何外部评分测试集。
_Avoid_: test-gate（易与已否决的 compile-gate 混淆）；compile-gate（被 Phase 0 证据否决的另一形状）

### MCP 工具发现

**MCP 工具检索与按需激活（MCP Tool Retrieval and On-Demand Activation）**:
MCP server 连接与 `tools/list` 发现保持 eager；发现到的完整工具描述进入共享的[[MCP 工具目录]]，默认作为[[延迟 MCP 工具]]不直接暴露完整 schema。只有[[常驻可见工具]]与当前 Agent / [[任务]]的[[已激活工具集]]会发送给 LLM；`tool_search` 采用“搜索即激活”，一次调用检索并激活有界 Top-K，命中工具从下一次 ReAct LLM 调用起可调用，不另设激活工具。
_Avoid_: MCP 工具延迟加载（未说明延迟连接、发现还是 schema 暴露）；lazy MCP startup；lazy `tools/list`

**MCP 工具目录（MCP Tool Catalog）**:
由所有已连接 MCP server 的 `tools/list` 结果构成的共享目录，保存工具名称、描述、input schema、所属 server 与调用桥接。目录不是模型当前可见的工具列表；`notifications/tools/list_changed` 会刷新对应 server 的目录项。

**延迟 MCP 工具（Deferred MCP Tool）**:
已进入[[MCP 工具目录]]、但完整 schema 暂不发送给 LLM 的 MCP 工具。它是 MCP 工具的默认状态，除非被 `tool_search` 激活或被配置为[[常驻可见工具]]。
_Avoid_: 未加载工具（容易误解为 server 未连接或 `tools/list` 未执行）

**常驻可见工具（Always-Visible Tool）**:
无需检索激活就持续向 LLM 暴露完整 schema 的工具集合，包括核心内置工具、`tool_search` 与显式配置的 MCP 例外工具。它不是 MCP 工具的默认状态。

**已激活工具集（Activated Tool Set）**:
某个 Agent / [[任务]]当前向 LLM 暴露完整 schema 的 MCP 工具集合。它属于该 Agent / 任务私有的 conversation lifetime 状态：跨回合保留，任务空闲后续派仍保留，但不跨新对话、`/clear` 或任务回收传播。

**MCP 结果引用化（MCP Result Referencing）**:
把 MCP 工具返回的大体积或非纯文本结果保留为可检索引用，同时只把紧凑摘要、类型信息和必要元数据放进当前热上下文。它覆盖 structured content、resource-like result、文件型结果和大输出截断，不等同于普通 MCP Resources。
_Avoid_: MCP resource caching（范围太窄）；tool result truncation（只描述退化行为）

### Skill

**Skill 指令注入（Skill Instruction Injection）**:
`load_skill` 命中的 `SKILL.md` 正文应作为同一 Agent / [[任务]]内的一次性指令上下文注入，在对应工具结果之后、下一次 LLM 调用之前以内部 user message 形式出现。它不是普通工具事实结果，也不等待下一条真实用户输入。
_Avoid_: Skill 工具结果注入；下一轮真实用户输入前置注入

### Agent 编排

**任务（Task）**:
主 Agent 在会话内派生的一次异步代理执行，拥有独立的对话历史、与主 Agent 并行运行，在任务注册表中按生命周期被追踪：运行 → 空闲（保留历史、可被**续派** continue）→ 回收。会话绑定、内存级、不跨重启；结果以通知回灌主 Agent 对话，用户经 `/task` 查看与干预。
_Avoid_: DurableTask、持久化后台任务（旧 SQLite 队列语义，已退役）

**前台 / 后台派生（Foreground / Background spawn）**:
`spawn_agent` 的两种模式：前台本轮阻塞等结果（多个前台派生经[[工具批次]]并行）；后台立即返回 `task_id`、本轮继续，结果经事件循环 push 回灌主 Agent。仅**交互模式**下前台超时（默认 ~2min）自动转后台（auto-background）；headless 关闭、前台无限阻塞。此处「后台」指内存级异步派生，非已退役的持久化后台任务。

**附着 / 游离（Attached / Detached）**:
任务相对前台回合的状态：有前台回合正在同步等待它 = 附着；无人等待 = 游离（显式后台派生，或前台任务超时 auto-background 后转入）。ESC 只取消当前前台回合及其仍**附着**的任务；**游离**任务不受 ESC 影响，经 `/task cancel` 单独终止。

### 终端渲染

**转写区（Transcript）**:
已经写入终端原生 scrollback 的会话输出，一经写出即不可变；用户依赖终端原生的滚动、复制、搜索能力访问它。
_Avoid_: 历史区、聊天记录（与对话历史 conversationHistory 混淆）

**活动区（Live Region）**:
终端可视区底部由渲染器独占重绘的连续区域，承载正在流式产生的输出、活动指示、审批提示、输入行与状态坞；高度受可视区约束，内容一旦滚出活动区顶端即冻结并归入[[转写区]]。
_Avoid_: live area、动态区、底部区（不精确）

**保留窗口（Retained Window）**:
渲染器在内存中保留、可重新渲染的最近会话片段，按**用户回合**计数（当前为最近 5 个回合：用户输入 + 该轮全部产出）。它是[[转写浏览层]]与[[可折叠块]]历史切换的数据来源，与[[转写区]]的不可变性互不冲突。
_Avoid_: 缓存转写、历史缓冲（未说明按回合计数）

**转写浏览层（Transcript Overlay）**:
按需进入的全屏浏览模式，渲染[[保留窗口]]内容，支持滚动与逐块折叠/展开，退出后主屏与 scrollback 原样保留。alternate screen 仅用于此层，不用于常规输出。
_Avoid_: 全屏模式、TUI 模式（与 Lanterna 遗留形态混淆）

**可折叠块（Foldable Block）**:
[[保留窗口]]内可在折叠头与完整内容间切换显示形态的渲染单元：工具调用组、围栏代码块、思考引用、文件 diff 均是；答案正文与用户输入不是。位于[[活动区]]内时可原地切换（Ctrl+O 命中输入行上方最近一个），滚出后只能经[[转写浏览层]]切换。
_Avoid_: 折叠区、collapsible section
