# PaiCLI

Java 实现的终端 AI 编程 Agent（ReAct 循环 + 工具调用），对标 Claude Code / pi agent 的核心能力。

## Language

### 工具执行

**文件互斥锁（File Mutation Lock）**:
对工作区文件做写入的工具必须持有的按文件粒度的互斥保证：同一文件的变更串行排队，不同文件照常并行。是文件写入并发安全的唯一正典机制。
_Avoid_: 读写分批、写串行、read/write partition（已废弃的全局串行方案）

**工具批次（Tool Batch）**:
同一条 assistant 消息里返回的一组 tool call，默认并行执行（上限 4 线程），结果按源顺序回灌对话历史。批内兄弟调用互相看不到对方的结果。

### Agent 编排

**任务（Task）**:
主 Agent 在会话内派生的一次异步代理执行，拥有独立的对话历史、与主 Agent 并行运行，在任务注册表中按生命周期被追踪：运行 → 空闲（保留历史、可被**续派** continue）→ 回收。会话绑定、内存级、不跨重启；结果以通知回灌主 Agent 对话，用户经 `/task` 查看与干预。
_Avoid_: DurableTask、持久化后台任务（旧 SQLite 队列语义，已退役）

**前台 / 后台派生（Foreground / Background spawn）**:
`spawn_agent` 的两种模式：前台本轮阻塞等结果（多个前台派生经[[工具批次]]并行）；后台立即返回 `task_id`、本轮继续，结果经事件循环 push 回灌主 Agent。仅**交互模式**下前台超时（默认 ~2min）自动转后台（auto-background）；headless 关闭、前台无限阻塞。此处「后台」指内存级异步派生，非已退役的持久化后台任务。

**附着 / 游离（Attached / Detached）**:
任务相对前台回合的状态：有前台回合正在同步等待它 = 附着；无人等待 = 游离（显式后台派生，或前台任务超时 auto-background 后转入）。ESC 只取消当前前台回合及其仍**附着**的任务；**游离**任务不受 ESC 影响，经 `/task cancel` 单独终止。
