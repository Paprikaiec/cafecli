# PRD: 会话内异步任务 substrate（多 Agent 自主编排）

Status: ready-for-agent
Date: 2026-06-22
Branch: TBD（建议 `feat/async-task-substrate`）
Decision record: ADR 0004（弃用 /team）；ADR 0005/0006/0007 待写（substrate+事件循环 / Java21 虚拟线程+准入 / 每任务 ToolRegistry）；尊重 ADR 0001（文件互斥锁）、ADR 0003（JsonNode 入参）

## Problem Statement

PaiCLI 的多 Agent 能力目前是一条与主循环平行的、由人手动触发的确定性编排路径（`/team` → `AgentOrchestrator`）：规划者拆 DAG、固定 2 个 worker 并行跑批、reviewer 审查重试，**整条 `run()` 同步阻塞到全部跑完才返回**。这带来几个用户能直接感受到的问题：

1. **主 Agent 自己不会拆活、不会并行**。默认 ReAct 模式下，模型想"同时从三个角度调查一个 bug"做不到——只能串行地一个工具接一个工具跑。真正的并行只存在于人工切到 `/team` 之后，而 `/team` 又是一套和主循环割裂的固定流水线。
2. **跑 `/team` 期间整个会话被锁死**。用户不能插话、不能看进度、不能让一个长任务在后台跑着自己继续干别的；要等整棵 DAG 出结果。
3. **`/team` 的编排是写死的代码**，不是模型的判断：花名册固定（1+2+1）、并发固定为 2、"先规划再执行"是人为注入的先验。在 SWE-bench 这类评测里这等于给模型开了脚手架外挂，测的是脚手架不是 agent 本身。
4. **后台任务能力错配**。唯一的异步设施 `DurableTaskManager` 是为无人值守、跨重启、独立 fresh-context 的 headless 队列设计的（只服务 `/task`），无法承载"会话内、共享上下文、结果回灌对话"的子 Agent。

对标 Claude Code：委派在它那里不是一个独立模式，而是主循环手里的一个工具；子 Agent 是异步的、结果以通知注入回主对话；主 Agent 自主决定要不要 fan-out。PaiCLI 缺的正是这套"自主异步编排"的底座。

## Solution

引入一层**会话内异步任务（Task）substrate**，让**主 Agent 通过工具自主派生子任务**，成为唯一的多 Agent 编排模型；`/team` 的确定性编排与 `DurableTaskManager` 一并退役。从用户视角：

- 主 Agent 在一条 assistant 消息里发起多个 `spawn_agent`，借现有[[工具批次]]天然并行 fan-out（前台派生，本轮等结果）；也可 `background: true` 把任务放到后台、本轮继续，**任务完成时主动 push 一个通知回主对话**，主 Agent 自动续跑并把进展打印在输入行上方。
- 长时间的前台派生在**交互模式**下超过约 2 分钟自动转后台（auto-background），不再卡住用户；headless 下前台无限阻塞、不启用任何后台/事件循环设施。
- 用户用 `/task` 看当前在跑哪些任务、检视某个任务、单独取消某个游离任务；ESC 取消"眼前这件事"——当前前台回合及其仍**附着**的任务，**游离**任务不受影响。
- 子任务各跑各的、互不污染上下文：每个任务有独立对话历史与独立工具会话状态；完成后默认只把**结果**回灌主对话（不灌它的工具噪声），主 Agent 需要时再用 `task_output` 主动拉全量轨迹。
- 同文件并发写仍由[[文件互斥锁]]（进程级静态，ADR 0001）兜底，无论多少任务并发都不丢更新。

为支撑"主线程随时能被后台任务唤醒"，交互 REPL 从"readLine→同步跑一轮→打印"改造为**事件循环**：主线程退化为输入泵，单一串行执行器独占主对话历史，用户提交与任务完成都是队列事件，在回合边界统一 drain。每个任务跑在自己的**虚拟线程**（升级 Java 21）上，并发由**准入控制**兜底防线程爆炸/死锁。

SWE-bench 评测走 `--strategy=react`（自主模式），还原 agent 自身能力，不再用确定性编排做外挂。

## User Stories

1. As a PaiCLI 用户, I want 主 Agent 在默认模式下就能同时从多个角度并行调查一个问题, so that 我不用手动切到一个割裂的 `/team` 模式就能拿到并行探索的速度。
2. As a PaiCLI 用户, I want 让一个耗时任务在后台跑着、我继续在前台做别的事, so that 长任务不再锁死整个会话。
3. As a PaiCLI 用户, I want 后台任务完成时自动把结论推回对话、主 Agent 自动接着干, so that 我不必反复回车或轮询去"催"它。
4. As a PaiCLI 用户, I want 一个跑得太久的前台任务自动转入后台, so that 我不会被一个没完没了的子任务一直卡在那里干等。
5. As a PaiCLI 用户, I want 用 `/task` 看到当前会话里有哪些任务在跑、各自什么状态, so that 我对"主 Agent 正在并行干什么"有可见性。
6. As a PaiCLI 用户, I want 用 `/task cancel <id>` 单独终止某个跑偏的后台任务, so that 我能纠正一个方向错误的任务而不影响其他任务。
7. As a PaiCLI 用户, I want 按 ESC 时停掉"眼前这件事"（当前前台回合及其附着的子任务）, so that 我能快速中止当前动作。
8. As a PaiCLI 用户, I want ESC 不误杀我故意放到后台跑的游离任务, so that 我纠正一个前台小失误时不会丢掉后台正在跑的昂贵长任务。
9. As a PaiCLI 用户, I want 在我打字的时候后台任务的输出出现在输入行上方而不打断我的输入, so that 推送通知不破坏我正在编辑的命令。
10. As a PaiCLI 用户, I want 后台任务要执行危险操作（写文件/执行命令/MCP）时仍会向我请求审批, so that 我没有因为"放到后台"就失去对危险操作的把关。
11. As a PaiCLI 用户, I want 后台任务的审批提示标注是哪个任务在请求, so that 多个任务并发请求时我知道在批准谁。
12. As a PaiCLI 用户, I want 当我对某个工具点"全部批准"时，提示明确告诉我这会对本会话所有 agent（含正在跑/将来的任务）生效, so that 授权范围对我透明、不是悄悄扩散。
13. As a PaiCLI 用户, I want 一次审批的"全部批准"在本会话内对所有任务共享, so that 我不必为每个并行任务把同一个工具重复批准一遍。
14. As a PaiCLI 用户, I want `revert_turn` 能把工作区回退到某个主回合之前（连带那期间后台任务写入的改动一起回退）, so that 我能整体撤销"这件事"造成的全部工作区改动。
15. As a PaiCLI 用户, I want 关掉/重启 PaiCLI 不再保留旧的持久化后台任务队列, so that 任务语义统一为"会话内、内存级"，不再有跨重启的隐藏队列让我困惑。
16. As an LLM 主 Agent, I want 一个 `spawn_agent` 工具来派生自主子任务, so that 我能把研究/实现/验证委派出去而不必自己串行做完。
17. As an LLM 主 Agent, I want 在一条消息里发起多个 `spawn_agent` 就能并行跑, so that 我能对独立的子问题同时 fan-out。
18. As an LLM 主 Agent, I want `spawn_agent` 默认前台（本轮拿到结果）, so that 当我需要结果才能继续时直接拿到。
19. As an LLM 主 Agent, I want `background: true` 让任务异步跑、立刻拿到 `task_id`, so that 我有真正独立的活时能并行推进而不阻塞本轮。
20. As an LLM 主 Agent, I want 用 `send_message` 续派一个已空闲的任务（带上我综合后的新指令）, so that 我能复用它已加载的上下文与热缓存，而不是重开一个从零开始的任务。
21. As an LLM 主 Agent, I want 任务完成的通知默认只包含它的最终结果与用量、不含它的工具噪声, so that 我的上下文保持干净、派生才真的省了上下文。
22. As an LLM 主 Agent, I want 在结果不够时用 `task_output(task_id)` 主动拉取某个任务的完整轨迹, so that 少数需要细节的情况下我仍能查到它具体做了什么。
23. As an LLM 主 Agent, I want 任务结果以带标签的通知块、以 user 角色消息注入, so that 我能识别它"看起来像用户消息但不是用户"，不会去感谢它、而是把结论汇报给真实用户。
24. As an LLM 主 Agent, I want 续派一个已被回收的任务时得到"已过期，请重开"的明确反馈, so that 我知道该 spawn 新任务而不是误以为上下文还在。
25. As an LLM 主 Agent, I want 我派生的子任务最多享有与我同等的权限（不能凭空提权）, so that 委派不会变成绕过 HITL 的后门。
26. As a 子任务（Task）, I want 拥有独立的对话历史与独立的工具会话状态（LSP 诊断缓冲、Skill 缓冲、模型选择各自独立）, so that 我和其他并发任务之间不会串台。
27. As a 子任务（Task）, I want 与主 Agent 共享 MCP 连接 / LSP 进程 / 网络provider / 审计 / 长期记忆等昂贵服务, so that 不会因为每个任务各开一套而拖垮资源。
28. As a 子任务（Task）, I want 完成后进入"空闲但可续派"状态并保留历史, so that 主 Agent 能选择带着我的上下文续派我。
29. As a 子任务（Task）, I want 在空闲任务超过保留上限时按最近活跃度被回收, so that 大量任务并发时内存不会无界增长。
30. As an LLM 主 Agent, I want 把研究发现综合成带文件路径/行号的具体指令再派下一步（而不是"基于你的发现去修"）, so that 我对问题的理解不被下放给子任务、委派质量可控。
31. As a SWE-bench 评测运行者, I want 评测走 `--strategy=react` 自主模式、不启用确定性编排脚手架, so that 跑分还原的是 agent 自身能力而非人为注入的先验。
32. As a SWE-bench 评测运行者, I want headless 路径不引入事件循环/虚拟线程推送等交互设施、前台派生无限阻塞跑完, so that 无人值守批跑保持简单、可预测、可复现。
33. As a SWE-bench 评测运行者, I want 子任务并发受全局准入上限约束, so that 一次跑评测不会因任务暴涨而打爆 API 限流或内存。
34. As a 维护者, I want 并发子任务对同一文件的写入仍由进程级文件互斥锁串行兜底（ADR 0001 不被破坏）, so that 引入并发任务不会重新引入丢失更新。
35. As a 维护者, I want 任务隔离采取"默认隔离、共享靠显式注入"的边界, so that 将来新增的工具可变状态默认不会成为跨任务泄漏点。
36. As a 维护者, I want 准入控制同时有深度上限与"等待子任务时释放自身配额", so that 任务派生子任务时不会出现池被占满的死锁。
37. As a 维护者, I want 事件循环/任务注册表从 `Main` 中抽出成无终端依赖的可驱动单元, so that 核心编排逻辑能用桩 `LlmClient` 在无终端环境下被测试。
38. As a 维护者, I want `/team`、`AgentOrchestrator`、`DurableTaskManager` 及其测试随本期一并退役, so that 不再维护一条已决定弃用的割裂路径。

## Implementation Decisions

> 以下决策来自一次完整的设计 grill；术语遵循项目词汇表（[[任务]]、[[工具批次]]、[[文件互斥锁]]、附着/游离、前台/后台派生）。不含具体文件路径与代码。

**Substrate 身份与退役**
- 新增一个**内存级任务注册表（TaskRegistry）**，持有会话内所有 [[任务]] 及其生命周期状态（运行 / 空闲-可续派 / 已回收）。
- **退役** `DurableTaskManager` / `DurableTask` / `TaskCommandFormatter`：会话内任务一律内存级、会话绑定、不跨重启。跨重启/无人值守的持久化诉求由 `run --file` 与 `serve --http`（独立的 `RuntimeThreadStore`）继续承担，二者不受影响。
- **退役** `AgentOrchestrator` 与 `/team`（见 ADR 0004）。多 Agent 编排唯一模型 = 主 Agent 自主派生。`/plan`（`PlanExecuteAgent`）保留，价值点不同（可审阅的单 Agent 计划）。
- `/task` 命令**重指向**任务注册表：`list` 看在跑的任务、检视单个任务、`cancel <id>` 终止单个任务；不再读 SQLite。

**执行模型（事件循环 + push）**
- 交互 REPL 改造为**事件循环**：主线程作为输入泵（常驻 readLine），**单一串行执行器**独占主对话历史并跑主 Agent 回合。`UserSubmit` 与 `TaskDone` 都是事件队列上的项，在回合边界统一 drain（多个任务同时完成 → 一个回合看到全部）。
- 串行只约束**主 Agent 这一条对话**；子任务各有独立 `Agent` 实例与独立历史，**在各自虚拟线程上真正并发**。
- 后台任务完成时 **push**：投递 `TaskDone` 唤醒执行器跑一个自治回合，把结论以 `printAbove` 打印在输入行上方。

**运行时与并发（详见 ADR 0006）**
- **升级 Java 21**，每个任务及其阻塞等待跑在**虚拟线程**上（要求运行环境 JRE 21）。
- **全局准入控制**：`深度上限`（硬兜底，限制 主→任务→子任务 的派生层数）+ `等待即释放配额`（父任务前台等待子任务时释放自身准入名额，避免池占满死锁）。运行中任务受准入上限约束；空闲任务的留存由 LRU 回收管理，二者分离。

**生命周期**
- 任务完成 → 进入**空闲-可续派**（保留历史）→ 被 LRU 回收（按最近活跃度，保留约 5–10 个最近任务可续派；超出淘汰最旧）。被回收任务的 `task_id` 续派时返回"已过期，请重开"，但其一行结果摘要仍可在 `/task` 历史看到。
- 任务对话历史的 UI/transcript 镜像设每任务上限（默认约 50 条，对标 CC 的 `TEAMMATE_MESSAGES_UI_CAP`）。

**工具面（遵循 ADR 0003：新工具走 JsonNode/DTO）**
- `spawn_agent({description, prompt, background?})`：默认**前台**（本轮阻塞，结果即工具结果；多个前台派生经[[工具批次]]并行）；`background: true` 立即返回 `task_id`、本轮继续。
- `send_message({to: task_id, message})`：**续派**一个空闲任务（执行器在其保留历史上跑新回合）。
- `task_output({task_id})`：按需拉取某任务完整轨迹（默认不注入）。
- **auto-background**：仅交互模式，前台派生超过约 2 分钟自动转后台（detached）；headless 关闭、前台无限阻塞。

**隔离边界（详见 ADR 0007）**
- **每任务独立 `ToolRegistry`**；昂贵共享服务**显式注入**而非各自重建：MCP 连接、LSP 进程（`LspManager`）、网络 provider、进程级文件互斥锁、审计日志、长期记忆写入。
- 会话相邻的可变状态（LSP 待注入诊断缓冲、Skill 上下文缓冲、当前模型、短期历史）**每任务私有**。原则：默认隔离，共享靠显式注入。

**取消语义**
- 全局**取消令牌注册表**（主回合令牌 + 每任务令牌）。**ESC = 广播取消**当前前台回合及其仍**附着**的任务；**游离**任务（显式后台派生，或前台超时转后台）不受 ESC 影响。`/task cancel <id>` = 取消单个任务令牌 + 中断其虚拟线程。废弃 `CancellationContext` 的 `InheritableThreadLocal` 继承（每任务显式持有自己的令牌）。

**HITL（人在环）**
- `requestApproval` 不再在调用线程上读 stdin：调用（虚拟）线程**park 在一个 future** 上，主线程（泵）渲染审批提示并回收决定后 resolve。前台回合与后台任务**走同一条 pump 路由的审批队列**，串行展示（同一时刻只一个提示），每条请求**标注请求方**（主 Agent / `task_id`+标签）。
- 后台任务的审批**照常上浮给用户**（不静默拒绝、不静默 park）。
- "全部批准"沿用现有 per-tool/per-server 粘性，**会话级全局**（对所有 agent 生效），但**授权时明示生效范围**（"本会话所有 agent"），危险等级越高提示越显著（沿用 `ApprovalPolicy` 的 🔴/🟡 分级）。子任务权限 ≤ 派生者权限（不可提权）。

**快照 / 回退**
- 快照**只锚定串行主回合边界**（前台回合 + push 回合都在执行器上，时间线保持线性）；任务不单独快照，其写入折叠进下一个主回合的快照。`revert_turn` = 回退工作区到某主回合之前（连带期间折叠进来的任务写入），即**整体 effort 级回退**，非任务级外科回退。
- 已附着前台派生（[[工具批次]]内）在主回合期间写入 → 被该回合 post 快照正常捕获，无撕裂；撕裂风险只来自仍在写的游离任务，由文件互斥锁保证单文件写原子、并被下一次快照取代而缓解。

**Prompt 纪律（P0.1，已采纳）**
- 主 Agent 系统提示加入"continue-vs-spawn 选择"与"绝不下放理解"两条：把研究发现综合成带文件路径/行号的具体指令再派下一步；按上下文重叠度选择续派或重开；任务通知是"像用户但非用户"的信号、应汇报给真实用户而非感谢任务。

**评测**
- SWE-bench / 无人值守走 `--strategy=react`（自主模式），不启用确定性编排；原 reviewer/重试的价值迁移到 prompt 级验证纪律。

## Testing Decisions

好的测试只验证**外部可观察行为**，不绑定实现细节：喂确定性的 LLM 响应与事件，断言"产生了什么对话/通知/注册表状态"，不去断言内部调用次序或私有字段。

**复用既有 seam（`AgentOrchestratorTest` 已验证的模式）**
- **桩 `LlmClient`**：`StubGLMClient`（按序回放）/ `DispatchingStubGLMClient`（按最后一条 user 消息派发）+ `NoOpMemoryManager`。所有行为测试都骑这个 seam，在无终端、无真实 LLM 下驱动 agent 循环。
- **并发证明**：沿用 `CountDownLatch` + 峰值并发 `AtomicInteger` 的模式（已存在于"两个 worker 并发"测试），用来证明多个任务确实并发、且**准入上限**把峰值并发约束在设定值。
- **工具执行 seam**：`spawn_agent` / `send_message` / `task_output` 作为工具，断言其返回串——spawn 返回 `task_id`；`send_message` 指向已回收 id 返回"已过期"；`task_output` 返回轨迹。复用现有工具测试先例。

**新 seam（抽到最高点）**
- 把事件循环 + 任务注册表从 `Main` 抽出为**无终端依赖的 `AgentSession`（含 `TaskRegistry`）**单元：构造时注入 `LlmClient`（可桩）与可捕获的通知 sink；通过 `postEvent(UserSubmit | TaskDone)` 驱动，通过结果主对话、捕获到的 `<task-notification>` 块、注册表快照（运行/空闲/回收）观察。JLine 输入泵与 `printAbove` 渲染留在 `Main`、在 seam 之上，仅集成/手测。

**纯策略单元（最高 seam = 策略对象本身，类比 `AgentBudgetTest`）**
- **准入控制**：喂合成的 派生/等待 序列，断言准入/拒绝与**无死锁**（父等待子时释放自身配额）。
- **LRU 回收**：喂空闲态转换，断言到达上限时淘汰哪些任务。
- **ESC × 附着/游离**：喂注册表状态，断言恰好取消"当前回合 + 附着任务"的令牌、游离任务存活。

**HITL pump seam**
- 审批请求 → 调用线程 park 在 future；主线程回收决定后 resolve。双线程 + latch 验证 park/resolve 与请求方标注。

**随类退役的测试**
- `AgentOrchestratorTest`、`DurableTaskManagerTest` 随其被测类一并删除。

## Out of Scope

- **worktree 隔离**（CC 的 `isolation: "worktree"`）：作为 `spawn_agent` 的后续可加项与"任务级外科回退"的终局目标，本期不做；本期快照为 effort 级回退。
- **teammate + 共享任务列表**（CC 的常驻 teammate / TaskList claim 模型）：本期只做"一种可续派任务"，不做团队/认领层。
- **`subagent_type` / 自定义 agent 角色**（`.claude/agents/` 对等）：本期单一通用派生，类型化 agent 后续加，纯增量。
- **跨会话 / 跨机器消息**（CC 的 `uds:` / `bridge:` peer）：不做。
- **持久化、跨重启的会话内任务**：明确放弃；持久化需求归 `run --file` / `serve --http`。
- **push 回合的富 UI**（每任务进度行、可 zoom transcript、worker 角标渲染）：本期通知走 `printAbove` 文本即可，UI 细化后续。
- **token 自动损耗治理**：用户明确不在本期考虑后台自治回合的 token 成本。

## Further Notes

- **决策记录**：ADR 0004 已记录 /team 弃用；建议本期补 ADR 0005（substrate + 事件循环 REPL）、ADR 0006（Java 21 虚拟线程 + 准入控制）、ADR 0007（每任务 ToolRegistry + 注入式共享服务）。CONTEXT.md 已就 [[任务]] / 前台-后台派生 / 附着-游离 落词。
- **待定默认值**（设计中标为"采纳默认、未细抠"）：LRU 保留数 N（~5–10）、消息镜像上限（~50）、auto-background 阈值（~2min）、全局准入上限——实现时取合理值并设为可配置（对齐现有 `paicli.*` 系统属性风格）。
- **演进定位**：本期是"自主异步编排"的脊柱（spine）。worktree 隔离、teammate/共享任务列表、类型化 agent 都是此 substrate 之上的增量；确定性编排若日后出现真实用户诉求，可在 substrate 之上以数百行重建一个 driver（弃用非烧桥）。
- **关键不变量**：ADR 0001（进程级文件互斥锁）在并发任务下仍是同文件写安全的唯一正典机制——substrate 不绕过、不复制它。
