# Cafecli

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

### 无人值守修复（Headless Repair）

**能力闭合（Capability Closure）**:
模型可见工具面与实际接线能力的一致性约束：协作者未注入的工具不向 LLM 暴露 schema，能力接线后自动恢复暴露；对交互、无人值守、子任务所有组装路径一体适用。工具不可用时的正确形态是「不在工具面上」，而非「调用后返回不可用说明」。
_Avoid_: 禁用工具（像人工配置开关，不表达与接线状态联动）；隐藏工具

**仓库拓扑（Repository Topology）**:
按需从当前工作区构建的会话级结构地图：构建模块归属（Maven/Gradle）、测试源根、符号定义/引用与测试关联。它是定位证据、[[补丁可提交性]]、编辑后反馈与收尾相关性判断的共享底座；本身是服务不是 hook，不给出决定。
_Avoid_: 代码索引（与 RAG 语义索引混淆）；项目结构（太泛）

**补丁可提交性（Patch Admissibility）**:
求解与提交的分离：求解期允许写临时测试驱动验证推理，形成对外提交物时按[[仓库拓扑]]识别的受保护测试根剔除相应改动，只让生产修复进入提交补丁。与 safe-edit 不同层：safe-edit 是[[策略守卫]]在写入时的拒绝，可提交性是提交时的过滤；前者是后者落地前的过渡形态。
_Avoid_: 测试剥离、patch hygiene（只描述动作，不表达「求解可写、提交不可入」的约束语义）

**修复台账（Repair Ledger）**:
长轨迹压缩时保留的结构化修复状态：问题契约、已检查符号、假设及其证据与生死、当前 diff、验证结果、未决阻塞；与近期原始工具交换共同构成压缩后的热上下文，切分只落在完整 assistant/tool 交换的缝上。与[[读前写台账]]同用「台账」字但互不相关——那是写安全记录。
_Avoid_: memory retention ledger（描述机制不描述内容）；上下文摘要（丢失结构化与假设生死语义）

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
任务相对前台回合的状态：有前台回合正在同步等待它 = 附着；无人等待 = 游离（显式后台派生，或前台任务超时 auto-background 后转入）。[[取消键]]只取消当前前台回合及其仍**附着**的任务；**游离**任务不受取消键影响，经 `/task cancel` 单独终止。

### Agent 架构

分层与决策理由见 ADR 0020。

**回合（Run）**:
一次用户提交（或一次后台任务完成触发的自治回合）从开始处理到给出最终答案的全过程，由若干[[迭代]]组成。
_Avoid_: turn（pi 的 turn 指的是[[迭代]]）；轮（与迭代混用）

**迭代（Iteration）**:
[[回合]]内的一次 LLM 请求加上它返回的[[工具批次]]。[[转向消息]]、LSP 诊断等都落在迭代边界上。
_Avoid_: turn、轮

**辅助调用（Auxiliary invocation）**:
由 [[Harness]] 或工具发起、经同一权限链执行的工具调用，不是模型请求的[[工具批次]]成员。它有自己的完成记录，但不单独构成模型的 assistant/tool-result 日志；模型需要的结果由发起者呈现。
_Avoid_: 绕过权限的内部调用；把嵌套调用当作另一轮模型工具调用

**Agent 日志（Agent log）**:
一个 Agent 的对话里发生过的一切，按发生顺序只追加、带类型的记录。它是对话的唯一事实来源，本身从不原样发给模型。
_Avoid_: 对话历史 / conversation history（指「记录即请求、原地改写」的旧形态）；短期记忆（已退役的第二份历史）；提交日志（渲染侧的有序提交日志，另一回事）

**上下文投影（Context projection）**:
为一次[[迭代]]从[[Agent 日志]]算出的、真正发给模型的请求。同一份日志在不同时刻可以投影出不同的请求，投影从不回写日志。
_Avoid_: 不加限定的「投影」（渲染侧也这么叫按宽度排出的样式行）；上下文窗口（那是模型的容量，不是内容）

**准备呈现（Prepared presentation）**:
输入准备时一次性确定并记录的用户消息呈现，供之后的[[上下文投影]]复用；它与用户原文及前缀来源分开保留。它不是完整请求，重放时不重新读取图片文件或剪贴板。
_Avoid_: 把准备呈现当成用户原文；把一次输入的准备结果当成整个上下文投影

**投影阶段（Projection stage）**:
[[上下文投影]]里的一步纯变换：输入日志视图、输出日志视图，每次迭代都跑，从不写日志。
_Avoid_: 注入（暗示改写了记录）；中间件

**日志写入者（Log writer）**:
在约定时机（回合开始、迭代边界、上下文溢出）向[[Agent 日志]]**追加**条目的组件。压缩是写入者：追加一条「覆盖第 i..j 条」的摘要，被覆盖的条目原样留在日志里。
_Avoid_: 压缩即删除 / 原地替换；把需要调用模型、或结果不稳定的工作放进[[投影阶段]]

**边界收件箱（Boundary inbox）**:
一个 Agent 唯一的、在[[迭代]]边界被取空并追加进[[Agent 日志]]的待处理队列；[[转向消息]]、LSP 诊断、Hook 回灌、任务通知都从这里进。条目为什么到达由它的[[自定义消息类型]]表达，不由通道表达。
_Avoid_: 每种来源一条专用注入通道

**自定义消息类型（Custom message kind）**:
由 [[Harness]] 而不是 Agent 核心定义的[[Agent 日志]]条目类型（记忆召回、LSP 诊断、任务通知、压缩摘要……），自己声明在[[上下文投影]]里给模型看成什么，或者不给看。
_Avoid_: 系统消息（暗示进 system prompt）；伪装成普通 user 消息（失去类型，事后无从区分）

**Harness（产品层）**:
把通用 Agent 做成编码 CLI 的那一层：工具、权限链、Hook、MCP、记忆、上下文策略、任务与子 Agent、会话。它在 Agent 核心之上、UI 之下，UI 与无人值守入口只经它的会话门面说话。
_Avoid_: agent 层（与 Agent 核心混淆）；框架

**能力（Capability）**:
[[Harness]] 里一个可以单独重写的领域（MCP、Skill、记忆、任务、LSP、快照……），经同一个内部接入点贡献工具、[[投影阶段]]、[[日志写入者]]、命令等，状态分会话级与 Agent 级。没挂到某个 Agent 上的能力对它什么都不贡献，这就是[[能力闭合]]。
_Avoid_: 插件 / plugin、扩展 / extension（暗示加载隔离与对外 API，这里都没有）；模块（太泛）

**Agent 配置档（Agent profile）**:
构建一个 Agent 的配方：模型、system prompt、挂上哪些[[能力]]、预算（硬[[迭代]]上限）、上下文流水线（由挂上的能力贡献的[[投影阶段]]与[[日志写入者]]组合而成）。主 Agent 与子 Agent 都由同一个工厂按配置档构建；配置档没挂的能力对这个 Agent 什么都不贡献（[[能力闭合]]），挂上的能力按会话里的声明顺序、而不是配置档里的列举顺序参与。
_Avoid_: 角色 / role（暗示 prompt 人设，配置档还管能力与预算）；模板（只是其中的 prompt 那一项）

### 快照

**快照绑定（Snapshot binding）**:
快照仓库与被快照工作树之间的关系：**gitDir 是仓库的属性，工作树是调用的属性，两者不得互相持久化**。快照仓库以 **bare** 形态在 `~/.cafecli/snapshots/<project>/<worktree>/.git` 创建——创建期根本不告诉 JGit 有工作树，因此没有任何东西可以被写进用户项目；随后 `core.bare=false` 使它可以接受外部工作树。每次快照 / 恢复各自用 `FileRepositoryBuilder.setGitDir().setWorkTree()` **现场绑定**，等价于每次显式传 `--git-dir` / `--work-tree`；`core.worktree` 永不落进 config。这不是风格偏好：创建一个 gitDir 位于工作树之外的**非 bare** 仓库，JGit 按 `--separate-git-dir` 语义往工作树根写一个指回 gitDir 的 gitfile——项目根的 `.git` 若本身是 gitfile（linked worktree / submodule）就被**覆盖**，此后该目录下所有 git 操作解析到快照仓库，静默改写历史归属。见 ADR 0018。
_Avoid_: `Git.init().setDirectory(projectRoot)` 与 `Repository.create(false)`（两者都写那个 gitfile，已实测证伪——副作用属于「创建非 bare 仓库」这件事，不属于 `InitCommand`）；把 `core.worktree` 写进快照仓库 config（绑定不是仓库的属性）

**被劫持的项目（Hijacked project）**:
项目根的 `.git` 已经是一个指向 `~/.cafecli/snapshots/…` 的 gitfile——bug 期的 Cafecli 在 linked worktree / submodule 里跑过留下的痕迹。检测（`HijackedProjectGit`）是**只读**的，命中即**停用快照 + 打印指引**，不自动改写：从这个指向推不回原始 gitdir，`git worktree repair` 必须从主仓库那侧发起。见 ADR 0018 决策 4。
_Avoid_: 自动「修好」它（猜错就是第二次静默改写，而且发生在用户已经知道有问题的时候）；把它读成「快照坏了」（坏的是用户项目的 `.git`，快照只是不能再碰它）

### 终端渲染

**活区（Live region）**:
终端底部被原地重绘的连续区域，由单一渲染线程独占写出：[[活区尾]] + [[输入框]] + 状态坞。区域内一切内容——含用户正在编辑的文本与光标——都由同一个写者合成为一帧；行编辑器只交出「要显示的行 + 光标位置」，不自己画终端。区域外的一切都是[[冻结回卷]]。区域里的每一行都是自己的[[逻辑行]]，与[[冻结回卷]]同一种形态。
_Avoid_: 输入行与流式输出各自持有终端（两个写者，已否决）；活动区条带（只指其中的[[活区尾]]）

**活区尾（Live tail）**:
[[活区]]中承载本回合瞬时输出的部分——思考 spinner + 答案流式尾 + 审批卡 + 尚未拿到结果的[[工具块]]（含运行中命令的输出尾）。完成内容一律提交为[[冻结回卷]]。瞬时内容**从不提交**，所以运行中的工具块在[[转写浏览层]]里看不到，要等结果落定——这是明码标价的代价，而不是遗漏：提前提交等于往有序提交日志里放一个可变条目，正是[[条目源与视图缓存]]要避免的。见 ADR 0012、0017。
_Avoid_: 原地折叠 / 展开可折叠块、「活动区条带里重绘整段答案」（已退役）；把「还没有结果的工具块」读成「被推迟冻结的完成块」（后者是被否决的那个）

**逻辑行（Logical line）**:
终端自己计数的那种「行」：写者主动结束的一行是一条，因写满列而换的一行是上一条的**软折行续行**、与上一条合为同一条。选中、复制、终端内查找、以及终端自己的重排，处理的都是逻辑行而不是单元格网格。[[活区]]的每一行都必须是自己的逻辑行——不补硬换行时 JLine 会用「顶到右边缘 + 写下一行首字符 + 自动折行」下行，那正是续行的编码，整片活区会被并成一条 `n × width` 的逻辑行，用户复制走的就是一整条（ADR 0010 修订 A18）。**它与单元格网格是两回事**：两种写法画出的格子完全相同，所以只看格子的断言对此结构性失明，判据必须读软折行重接后的序列化。
_Avoid_: 物理行 / 屏幕行（指的是格子那一层，正相反）；「一行」不加限定（歧义正落在本条的分界上）

**输入框（Input box）**:
[[活区]]底部常驻的用户输入区域：空闲、模型流式输出中、等待审批时形态一致，不随回合出现或消失。它是用户意图的**唯一入口**——任务提交、斜杠命令、[[审批应答]]都经它提交，因此终端按键在任何时刻都只有一个消费者。模型输出期间键入的字符进入输入框，不被丢弃。
它读起来是一个**框**而不是两条分隔线，靠的是「整行底纹」而不是边框：底纹与[[提交块]]是同一片，所以空框也看得见（这才是「任何时刻都在」的可见证据），而提交时那片底纹原地上移成块，读起来像这个框把内容冻上去。竖直边框被有意避开——输入行是行编辑器按终端宽度排好交过来的，给它加左右边框就得让行编辑器按一个不是终端宽度的宽度排版。
_Avoid_: 输入提示符 / prompt（只描述一行，不表达常驻区域）；typeahead 缓冲（已否决的降级形态，指「回合期间另设一个简化编辑器」）；用边框表达「框」（要向行编辑器谎报宽度）

**提交块（Submitted prompt block）**:
用户提交过的那一句在转写里的样子：整行暗色底纹、首行一个紫色 `›`、续行缩进对齐正文，标记不重复。它与[[输入框]]共用**同一片底纹**，所以「正在框里编辑」与「已经提交」是同一个东西的两个状态，区别只在位置（[[活区]]里 / [[冻结回卷]]里）与暗度，而不是两套装饰——提交读起来像那个框把内容冻上去。
它保留的是**源文本**而不是烘好的行，所以 [[destructive resize reset]] 按新宽度重新渲染它。这是必须的：底纹补齐到整行，一旦按提交时的宽度烘死，窄化重画就会留下一截参差的半行。
_Avoid_: 编辑态标记 `* `（已退役，编辑与提交都用 `>` 家族）；把它当成一条普通流式文本落回卷（普通文本按提交宽度保留是有意的，提交块不行）

**取消键（Cancel key）**:
ESC 与 Ctrl+C 共同的语义：**有回合在跑就停回合**——取消当前前台回合与仍[[附着]]的任务、拒绝待决[[审批应答]]、丢弃未生效的[[转向消息]]，[[输入框]]里已键入的文本原样保留。取消是协作式的，落在回合边界而非流式回答中途。两键只在无回合可停时分岔：ESC 无操作；Ctrl+C 清空输入框，输入框已空时连按两次退出 Cafecli。
**模态开着时两键分岔，理由是送达方式不同**：ESC 是一个字节，得由当前占着字节流的人读到，而[[模态租借]]期间那个人是模态自己，所以 ESC 只对模态说话（层内 ESC 只关层）。Ctrl+C 是**信号**，不经字节流送达，因此不受租借影响——它在任何地方都保持同一个含义，包括模态开着的时候。这条差别是机制上的，不是约定出来的。
**Ctrl+D 是第三个键，但不是取消键**：它只在没有东西可停、且[[输入框]]已空时退出；其余三格无操作。框里有字时行编辑器根本不把它读成「输入结束」（走内建的整字符删除），回合在跑时它被忽略——它就在 Ctrl+C 旁边，按错的代价是整个会话。
**三个入口，一份实现**：常驻行编辑器上的 ESC widget、`readLine` 抛出的用户中断异常（Ctrl+C）、以及模态自持读取时由模态认下的那个中断，都落到同一个取消例程上——写成三份的代价不是重复代码，而是三份会分头漂移的取消范围。ESC 的触发窗口由 JLine 的 `ambiguous-binding`（100ms，经构造器 `variables` 传入）承担，与被删掉的手写转义分类器同一个量级（实测 106ms，不设则 1006ms）。`readLine` 之外的间隙由会话级[[信号兜底]]兜。
**拒掉待决[[审批应答]]是取消的一部分，不是附加动作**：一个 park 在审批上的请求方不会因为令牌被取消而醒过来，不放掉它，「回合停下来」就落不到实处，而屏幕上还立着一张卡片在等 y/n。
**取消不关模态，而反馈在层里看得见**：「已请求取消」经有序提交日志进[[转写浏览层]]的实时跟随，所以读者不必关层就知道自己那一下生效了——这正是「不关层」能成立的前提。
_Avoid_: 中断 / interrupt（易读成进程级信号）；ESC 清空输入（已退役的双重语义）；「模态开着时取消键一律只对模态说话」（把 ESC 的字节流限制误当成两键共同的性质）

**信号兜底（Session-level signal guard）**:
在装配期挂到终端 INT 信号上的会话级处理器，覆盖那些没有 `readLine` 在跑、因而 JLine 自己的 INT 处理器不在位的时刻（JLine 在 `readLine` 期间装自己的、退出时还原前一个，所以两者不冲突，空闲态一字未改）。**行编辑器常驻之后这些时刻少了很多**：等待泵与审批读键都已随 regime 一并退役，剩下的是装配期与斜杠 palette、项目 hook 信任确认这类仍在 `readLine` 之外的读取。跑在 `readLine` **里面**的模态（[[转写浏览层]]是个 widget）盖不住它，只能自己认下那个中断——JLine 的 handler 中断读线程，中断以 `InterruptedIOException` 从模态自己的读里浮出来，兜底一个字节都看不到；所以同一个 `interrupt()` 也被注入给渲染器，由浏览层调用。它是[[取消键]]中 Ctrl+C 那一侧的送达机制，不是一套并行语义：规则同样是「有回合在跑就停回合，没有就什么都不做」。它**从不写终端**（只经 `Renderer.stream()` 说话，由渲染线程落盘），也**从不关模态**。
_Avoid_: 「进程级中断处理」（它不终止任何东西）；「空闲时改回默认处理器」（被否决：那让规则带上条件分支，代价是 Ctrl+C 不再是卡死进程的逃生口，明码标价接受）

**转向消息（Steering message）**:
用户在回合运行期间经[[输入框]]提交、注入当前回合而非排队到下一回合的输入。它在下一次 LLM 调用前作为清晰标记的 user 消息生效——即落在回合的迭代边界上，与 LSP 诊断、PostToolUse hook 上下文同一条前置注入通道，粒度与[[取消键]]相同（流式回答不可中途打断）。提交即在转写中留下 `›` 块（源形态的[[提交块]]），并在[[保留窗口]]里占据**它被提交的那个位置**——所以在[[转写浏览层]]里它出现在答案被打断处，而不是整个答案之后。回合在下一个迭代边界到来前就结束时，未生效的转向消息退化为下一回合的普通提交，不得静默丢失。「有没有回合能接它」与「把它放进去」是同一个原子动作，否则回合可能恰好在两者之间结束、消息落进再也没人 drain 的槽里。
_Avoid_: 排队消息 / queued message（那是被否决的「等本回合跑完再说」语义）；打断（转向不停止当前回合）

**审批应答（Approval reply）**:
用户对一次 HITL 审批的回答，经[[输入框]]提交（`y` / `n` / `a` + Enter，另有 `s` 跳过与 `m {json}` 改写参数），与普通提交同一通路、同一按键消费者。空输入提交表示「再问一次」——重新呈现审批卡而不给出决定，不误判为默认拒绝；无法识别的回答同形，带一句提示重问。**没有决定就不把它从队列里取走**，所以请求方原样 park 着。「问」与「答」分处两条线程：卡片由渲染线程画 / 更新 / 撤掉，按键只由输入线程读，因此「渲染线程上任何终端读取都是死锁 bug」从被观察到的纪律变成结构上不可违反。审批**入队时的通知先于它变得可取出**：反过来时，正在 poll 的主线程可能抢先取走并 resolve，于是通知讲的是一条已不存在的审批。有[[转写浏览层]]开着时这条通知会关掉浏览层，所以它必须是准的。
_Avoid_: 审批按键 / 单键审批（已否决：需要第二个终端读者）；先入队后通知

**冻结回卷（Frozen scrollback）**:
已完成输出提交给终端不可变回卷区的形态：不再被重绘、不在默认流里原地展开；[[折叠块]]以折叠形态入回卷，展开态只在[[转写浏览层]]重建。不可变性只在一个 [[geometry epoch]] 内成立：[[destructive resize reset]] 会连同终端保存的回卷一起把它丢弃，之后屏上只剩 [[bounded replay window]] 重画出来的内容。
_Avoid_: 跨缩放持久的历史（终端保存的回卷不跨 [[geometry epoch]] 存活）

**模态租借（Modal lease）**:
一个模态——[[转写浏览层]]、斜杠 palette——独占终端的那段时间。租借期内渲染循环**一个字节都不写**：不是内容、也不是那半个与几何量无关的擦除，因为擦除虽然在任何尺寸下都安全，却不安全于**别人的屏幕**。这是把「同一时刻只有一个写者」这条不变式**扩大适用范围**，不是弯折它——模态在它的存续期间就是那个写者。
几何量照常被观察：租借期间的尺寸变化仍然结束一个 [[geometry epoch]]，但 [[destructive resize reset]] 被**推迟**——归还终端后恰好触发一次（变了才触发，没变则一次都不触发）。模态自己处理自己的缩放，这是**容易**的那一半：它占着整个 alternate screen，底下没有回卷要保住，所以无条件擦掉重画，不必去问终端把东西重排到哪儿去了。之所以不信终端自己的 `rmcup` 恢复，是因为它在没人看的时候重排了那片内容，而不信任这件事正是整套改动存在的理由。
租借在**帧边界**上交接：合成一帧与写出这一帧是同一个临界区，所以模态开画前，已经开始的那一帧一定已经写完，而租借之前合成的帧不可能在租借之后才写出去。
_Avoid_: 挂起 / 恢复状态坞（那对随 JLine `Status` 一起退役了）；渲染循环里判「有没有模态开着」的散落条件；用 `isReading()` 决定写不写
A period during which the terminal geometry does not change — neither dimension, width and height
alike; the two are never separated into reflow-relevant and irrelevant changes. Within one epoch [[冻结回卷]] behaves
as always — completed output goes to native terminal scrollback, is never repainted, and the
terminal owns scrolling, copy, and search. A geometry change ends the epoch; the next one starts
with a [[destructive resize reset]]. Native scrollback is durable within an epoch and only within
one.
_Avoid_: visibility epoch (the withdrawn `/clear`-scoped concept); session (one session spans many
epochs)

**Destructive resize reset**:
What happens at a [[geometry epoch]] boundary: the visible primary screen **and** the terminal's
saved scrollback are erased, then the [[bounded replay window]] plus the current [[活区]] are
redrawn at the new geometry. It is intentionally lossy — it can erase shell history written before
Cafecli started, and every Cafecli line older than the replay window — and that loss is accepted
rather than worked around. It is not a repaint of previously committed rows: the previous epoch's
screen is declared void, not reconstructed. Terminals that ignore erase-saved-scrollback get no
duplicate-free guarantee. **The erase is ordered screen-first, scrollback-second, cursor-home-last**,
and the order is load-bearing rather than stylistic: Terminal.app's screen erase *fills* the saved
scrollback with the screen it is erasing (the behaviour that leaves `clear(1)`'s output scrollable),
so erasing the scrollback first only deposits the screen straight back into it (ADR 0010 amendment
A19). Its two halves are not simultaneous: the erase is geometry-independent and
repeats throughout [[geometry turbulence]], while the redraw happens once, when the geometry settles.
The redraw half carries its own erase rather than trusting the eager one to still be the last thing on
the wire, so it homes the cursor itself instead of inheriting a position — a dozen bytes buys a
self-contained half.
_Avoid_: reflow on resize (implies the previous content is preserved); erase-distance arithmetic
(the falsified approach it replaces); clear screen (does not say the scrollback goes too)

**Geometry turbulence**:
The interval during which dimension changes are still arriving — a window drag or a fullscreen
animation, as opposed to the single instant the [[geometry epoch]] model might suggest. Cafecli
writes no content at all while it lasts: the screen stays blank, on purpose, and producers keep
filling the source model without reaching the terminal. It ends when the geometry is observed to
have settled, which is what releases the redraw.
Its two boundaries are timed, and both numbers are measured rather than chosen: the redraw is
released after a **quiet interval** of 100 ms without a new dimension, and a **max defer** of 500 ms
caps how long the screen may stay blank, after which a **keep-alive** redraw goes out even though the
gesture has not ended. A keep-alive does not end the turbulence — it bounds blank time, it does not
decide that the window stopped moving.
Turbulence is what makes the terminal's repaint queue one deep *by construction*. That matters
because Java observes when it submits bytes, not when the terminal consumes them relative to its own
reflow queue, so "the last repaint cleans up the earlier ones" is not a claim that can be verified
from inside the process — whereas "only a dozen bytes of erase were submitted" is.
_Avoid_: resize event (a single change, not the interval); debounce (names the mechanism, not the
thing being observed); flicker (turbulence is the cause, not the symptom); throttling the redraw
(implies dropped repaints of equal standing, when in fact one half is written eagerly and the other
exactly once)

**Geometry sampling**:
How Cafecli learns the terminal's dimensions, and therefore what "observed" means everywhere a
[[geometry epoch]] or [[geometry turbulence]] decision is made. The kernel is asked directly, with a
native `TIOCGWINSZ` probe; the resize signal is demoted to a **wake-up hint** that arms a fast probe
and is never the authority. It cannot be the authority, because it is not a faithful record in either
direction: one paired capture delivered 406 signal markers against 284 distinct sampled transitions,
so signals arrive without a new geometry and distinct geometries are coalesced out of delivery.
Sampling is a **push**: a driver polls at roughly millisecond resolution while the geometry is moving
and **parks** onto a slow heartbeat once it settles, so an idle session performs no polling and a
terminal that delivers no signal at all is still covered. Reading the geometry is a field read, which
is what takes the fork off the write path — and with it the old rule that geometry must not be
re-measured immediately before writing, which existed only because measuring cost a subprocess.
Whether a source may be polled fast is a question about **cost**, not accuracy, so a session without
the native probe keeps working and simply never polls fast.
_Avoid_: SIGWINCH handling (names the hint, not the channel); polling the terminal (a parked sampler
is not polling, and the terminal is not who is asked); resize callback (nothing is delivered — it is
looked at)

**Bounded replay window**:
The bounded, in-memory, application-held recent session content that a [[destructive resize reset]]
redraws. It is what survives a [[geometry epoch]] boundary; everything older does not. It is
presentation state rebuilt from source blocks, not a durable record — nothing is written to disk and
nothing outlives the process.
Its bound is a **row budget in screenfuls of the current terminal height** (default ×3), filled
backwards from the newest committed item until the budget is met. The budget is **advisory, not a
cap**: whole items render, so the item that crosses it is drawn whole rather than cut, and at least
one item is always drawn — a reset must never hand back an empty screen. Overflow scrolls into the
freshly erased scrollback, so the user still has real scrollback to page up into. Which items
survive is therefore width- and height-dependent, which is correct for a bound whose purpose is to
cap repaint cost rather than to preserve a fixed amount of history.
It is cut from [[保留窗口]] rather than being it: the two are separate bounds serving consumers with
different cost structures.
**Order is part of it, not incidental to it.** The window repaints in the order output was
committed, and the terminal writes committed rows in that same order, out of a single queue — so a
reset cannot reorder the transcript (ADR 0010 amendment A8b). Ordering is at **item** granularity: an
item that commits incrementally can be split in scrollback by another item, and replay then shows its
whole committed prefix before that other item. Nothing is lost or duplicated; one interleaving is
coarsened.
Membership is decided by **whether output was committed**, never by which thread produced it or by
what the line editor happened to be doing at the time. Routing committed text on
`LineReader.isReading()` once made membership depend on the latter, and a resize then destroyed
every transcript row written outside `readLine` (ADR 0010 amendment A8a) — committed and unrecorded
is the one combination that must not exist.
It is what the **user** sees, and only that. What the model sees is conversation history, which is
built independently, bounded independently by token budget rather than by rows, and compacted on its
own schedule. Nothing in the replay window is sent to a model, and nothing in conversation history is
drawn from it; the two never need to agree.
_Avoid_: durable session transcript; semantic event journal; full-session history (all three belong
to the withdrawn ADR 0010 reopening); terminal scrollback; conversation history (the model's context,
a different thing with a different bound)

**保留窗口（Retained window）**:
本会话**全部**已提交输出的有序记录——既包括源块（用户输入 / 答案 / 思考 / 代码 / 工具调用 / diff），也包括开屏 Banner、斜杠命令结果、后台任务完成通知这类普通流式文本；凡是进[[冻结回卷]]的都在内，只有活区里的瞬时内容（spinner / 审批卡 / [[输入框]]）天然不在。会话绑定、内存级、不跨重启。它是"被裁出视图的那份底稿"，不是视图本身。
它与 [[bounded replay window]] 是**两个不同的界**，服务两个成本结构不同的消费者：保留窗口只花内存、由用户主动打开，因此不设上限；重画界要花终端 I/O、每次缩放自动付一次，因此按行预算截断。[[destructive resize reset]] 丢掉的输出仍留在这里——不再能用终端自己的滚动 / 复制 / 搜索够到，但没有消失。
它增长的是**源**，不是渲染行：排版与缓存归看它的那个视图（见[[条目源与视图缓存]]），因此会话跑得越久，多出来的是每条目一个整数的高度索引，而不是一份越堆越大的行列表。`/clear` 是会话期间丢掉它的唯一方式。
里面的一个位置是**一段源**，不是一个条目：增量提交的东西（流式答案）在被别的提交打断时占据两个位置，打断它的那条夹在中间。理由是「一个条目一个位置」会把位置钉在它**第一次**提交的时刻，于是之后提交的任何东西都排在它**全部**输出之后——[[转向消息]]的全部价值恰恰是位置，而它天然发生在答案还在流的时候。
_Avoid_: 五轮窗口（已放开）；持久历史（进程退出即消失）；按条目身份定位（把位置钉在第一次提交，交错就被抹平）

**条目源与视图缓存（Entry source / view-owned render cache）**:
[[保留窗口]]里的条目是**不可变的源**；把源排成某个宽度的行、以及为此存下的缓存，归**看它的那个视图**所有——[[活区]]一份（渲染线程），[[转写浏览层]]一份（输入线程），互不共享。谁排版谁缓存，因此也是谁释放：浏览层把条目移出工作集时丢掉的是它自己的行，不会动别人的。
这条边界的必要性来自[[模态租借]]与实时跟随的组合：租借只停「写终端」，不停「改源模型」（提交刻意在[[geometry turbulence]]闸之上跑，否则 agent 会因为有人在读而停住），所以浏览层开着的时候，渲染线程仍在往流式答案里追加。共享一份缓存就意味着两个线程同时改一个 `HashMap`——那是**损坏**，不是画面撕裂：轻则丢缓存条目，重则输入线程死在 `get` 里，而此刻终端在 alternate screen、读按键的正是它。
仍在增长的条目（流式答案）把源以不可变快照经 `volatile` 引用发布，读者拿到的要么是最新的一致快照、要么是更旧的一致快照，永远不是撕开的那一份。
条目上因此**不留任何**渲染方法之外的缓存出口：接口只给「进日志的是哪一份源」与「给我一份**新的**视图渲染」；每个视图各自持有它的行，条目本身连数一数、释放一次都做不到。规则由一条**与读者无关**的回归钉住：渲染一条保留条目不得改变它的任何一个字段（反射指纹逐类比对），并带一个故意在渲染里记忆化的反例桩证明它有牙。这一条比「今天只有流式答案会变」强的地方正在于它可以在评审时核对。
_Avoid_: 给块加锁让两个线程共享它（ADR 0014 已否决）；从输入线程借渲染循环的帧锁（守住耦合而不是拆掉它）；并发 map（治了损坏，留下跨视图释放缓存这个设计缺陷）；「今天只有流式答案会变，所以是安全的」（那是巧合不是规则）；为「复现一次竞态」花力气（复现成功证明的是 `HashMap` 的通用性质，不是这个产品的性质）

**冻结边界（Freeze boundary）**:
分开「已提交进[[冻结回卷]]」与「仍在[[活区]]里」的那个位置。它**存在源坐标里**——已提交的源行数——而不是渲染行 / 样式行坐标，且在每个宽度上按当前投影重新求值。理由是同一份源在不同宽度下的样式行数不同（实测只有表格会：单元按宽度折行，行高取最高的那个单元；正文的折行发生在样式行变物理行时，不进这个计数），所以一个按样式行计数的边界跨宽度使用时必然错——少算重画已提交内容，多算则把活区尾清空。
它也是**每 [[geometry epoch]] 一份**：[[destructive resize reset]] 会按新几何量从源重画，所以新 epoch 重新求值这条边界，不继承旧的。
边界**只落在 markdown 已经吐出内容的位置**：块闭合前不吐行，边界落进未闭合的块内部就会拥有没有任何投影安放得下的行。刷出点由源结构决定、与宽度无关，因此可以安全地跨过「没吐东西」的源行。
_Avoid_: 样式行计数 / 渲染行计数的边界；跨 epoch 继承的边界；把闭合块的行倒记回块内源行（这一趟会改写边界已越过的源行，等于把从未写出的行标成已提交）

**转写浏览层（Transcript overlay）**:
全屏可滚动视图，由 Ctrl+O 进入 / 退出（浏览层外无其它入口），滚轮或按键滚动的纯行视口（无按块光标）。默认展开[[保留窗口]]的工具 / 代码 / diff / 答案块，只把思考[[折叠块]]留成折叠头——浏览层是「它做了什么」的视图；层内按 Ctrl+T 一键展开 / 收起全部思考块（「它为什么这么做」），该态仅存活于本次浏览，重开即复位为折叠。默认流与浏览层复用同一套块渲染函数。见 ADR 0013。
空闲与回合运行中都可进入，且**实时跟随**：视口停在末尾时新内容自动跟进，向上翻阅后位置不动，不会被推着走，回到末尾即恢复跟随（「停在末尾」是从视口位置**推导**的，不是一个存下来的模式）。它**在末尾开层**——回合中开层就是为了看现在在发生什么——代价是开层时把文档整个量一遍，与「按 G 跳到末尾」同一笔；不为省这一笔去改视口坐标系。跟随之所以只是既有轮询上的一次比对，是因为**提交发生在[[geometry turbulence]]闸之上**：[[模态租借]]期间渲染循环一个字节不写，源模型照常增长。它展示的是[[保留窗口]]的**全部**会话历史，因此也是 [[destructive resize reset]] 之后回看旧输出的地方。浏览层内 ESC 只关闭浏览层，不是[[取消键]]——那个字节被浏览层读走了，取消分类器看不到它。Ctrl+C 不受此限（信号不走字节流）：层内按 Ctrl+C 照常取消回合，**且不关层**——取消不需要主屏上的任何东西，把屏幕从正在读的人眼前抽走要另有理由（[[审批应答]]有，取消没有）。
浏览层**没有[[输入框]]**，这不是省略而是边界：全量历史 + 实时跟随 + 自持缩放已经很接近一个全屏合成器，给它加上输入框就等于走进被否决的「常驻 alternate screen」形态。正因如此，浏览层开着时冒出来的[[审批应答]]会**关掉浏览层**：审批经[[输入框]]提交，而输入框在主屏；让它干等更糟——请求方 park 在一个**没有超时**的 get 上，用户读多久它就停多久，从 agent 那边看会话只是毫无缘由地停住。浏览层拿到的是一个「有没有待决审批」的布尔量而不是审批本身，所以它**画不出**审批卡；卡片仍由渲染线程画在主屏上。关层时**记住阅读位置**，重开即回到原处（在末尾则回到「跟随」），位置由调用方带进带出，浏览层自己不存。后台审批的通知与回车 drain 不变——关层是加在它旁边，不是取代它（全屏浏览层底下那条通知本来就没人看得见）。
它**只排版视口里的那几十行**，而不是排完整篇再切一刀：条目在视口够到它时才被排版，高度记下来，离开视口后行就被丢掉。这不是被否决的那套虚拟化——没有会话存储分页、没有语义锚点定位、没有按块版本号的版面缓存，视口仍然是一段绝对行号。它自持缩放：浏览层开着时改窗口，由浏览层自己重新测页高、擦掉整屏、按新宽度重排，因为此刻终端归它（[[模态租借]]）。
它读到的条目是**不可变的源**，排版与缓存归它自己（见[[条目源与视图缓存]]）——这是它能一边跟随一边不与渲染线程抢同一份可变状态的原因。
_Avoid_: 全局 Ctrl+T 入口（层外无 Ctrl+T）、浏览层内按块光标、默认就把思考铺开；一次性渲染快照（实时跟随取代了它，但它守的那条不变式——输入线程不碰渲染线程拥有的块——由[[条目源与视图缓存]]接手，没有作废）；整篇排版再切视口（会话一长，每次方向键都要重建一份五位数的行列表）；层内渲染审批卡 / 层内读审批按键；用一个需要有人清掉的「有审批」标志位（ESC 拒掉的审批没人消费，标志位会永久卡住后续每一次开层）

**过程（Process）**:
模型产出里「它做了什么」的那一半：思考与[[工具块]]。另一半是**答案**——正文 prose 与它自己的围栏代码（围栏代码由答案流切出，归答案，不归工具）。这两类之外还有**会话记录**（[[提交块]]、开屏 Banner、斜杠命令输出、后台通知），它属于会话而不属于模型产出，因此不在这条分界的任何一侧。见 ADR 0017。
_Avoid_: 把思考归进「工具调用」（改名成谎话）；把围栏代码归进工具产出（它的生产者是答案流）

**工具块（Tool block）**:
一批工具调用在转写里的样子：**调用头 + 结果，一个块**。头是 `ToolName(裸参数)`（多次同名调用退化为中文分组标签），结果是工具返回文本的裸头部；对写文件的工具，结果**就是** diff。它在[[活区]]里开出、只有头，结果到齐后才**完成**，完成之后才被提交进[[冻结回卷]]，且**从不在冻结之后改变**——它不是被推迟冻结的完成块（那是 ADR 0012 否决的），它是还没有结果的瞬时内容。上限按角色分：头用源单位（≤2 [[逻辑行]] / 160 字符，因为头是身份，应当跨宽度稳定），结果用视觉行（≤3 行，因为那是屏幕预算）——两者对代码 / diff 重合，它们裁行而不折行。见 ADR 0017。
_Avoid_: 工具调用（只指请求那一半，不含结果）；头与结果各成一块（后台通知或[[转向消息]]会插在中间劈开这一对）；把 100 源行读成「diff 的例外」（它是同一条「上限按结果类型定」规则的另一半）；把 `create_project` 读成漏接的写文件工具（它是脚手架生成器，不走 `persistFileContent`，按普通工具拿文本结果）

**折叠块（Foldable block）**:
思考与[[工具块]]——默认流里不完整展开：思考只出一行折叠头，工具块出调用头 + 截断的结果预览，溢出部分标 `… +N 行 (ctrl+o 查看全部)`。工具 / 代码 / diff / 答案在[[转写浏览层]]默认展开（工具结果**完整**展开，不设第二道上限——那会造出哪儿都看不到的内容；无界性在上游已由工具层截断与 MCP 落盘解决）；思考在浏览层仍是折叠头，须层内 Ctrl+T 才展开。代码 / diff 内联按 100 **源**行截断。

**块色条（Block accent）**（已退役）:
曾在折叠头左侧按类型着色的竖条（思考品红 / 工具青 / 代码蓝 / diff 黄）。实测竖条（尤其品红）观感突兀，已退役——折叠头改纯文本（dim），块类型靠标签文案分辨（`思考 N 行` / `ReadFile(...)` / `code: lang` / `Update path`）。见 ADR 0013。
