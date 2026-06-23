# 会话内异步任务 substrate：事件循环 REPL + push，取代 DurableTaskManager

为让主 Agent 能自主派生并行子任务（取代弃用的 `/team`，见 [ADR 0004](0004-deprecate-team-orchestrator.md)），需要一个"会话内、内存级、结果回灌主对话"的异步任务底座。现有唯一异步设施 `DurableTaskManager` 是为无人值守、跨重启、独立 fresh-context 的 headless 队列设计的（SQLite 持久化 + `recoverRunningTasks` + 只服务 `/task`），形状完全不匹配。同时，交互 REPL 当前是"`readLine` → 单线程同步跑一轮 → 打印"，主线程阻塞在回合内，后台任务完成时没有任何入口把结果注入回来。

## 决策

1. 新增**内存级任务注册表（TaskRegistry）**，持有会话内所有[[任务]]及其生命周期状态；任务会话绑定、不跨重启。**退役** `DurableTaskManager` / `DurableTask` / `TaskCommandFormatter`，`/task` 重指向该注册表（list / 检视 / cancel）。
2. 交互 REPL 改造为**事件循环**：主线程退化为输入泵（常驻 `readLine`），**单一串行执行器**独占主 Agent 对话历史并跑回合；`UserSubmit` 与 `TaskDone` 都是事件队列项，在回合边界统一 drain。串行只约束主对话这一条；子任务各有独立 `Agent`/历史，在各自线程上真正并发。
3. **push**：后台任务完成投递 `TaskDone` 唤醒执行器跑一个自治回合，输出经 `printAbove` 落在输入行上方。

## 被否决的方案

- **pull（不 push）**：任务结果排队、等用户下次提交才注入。否决：拿不到"主 Agent 随工作者汇报自动推进"的终局体验；用户明确选择 push、不计 token 自治成本。
- **扩展 `DurableTaskManager`**：给它加一条非持久、共享上下文的会话内路径。否决：把 SQLite 持久化/恢复/fresh-context 强加到每个短命研究子任务上是反的（292 个临时任务持久化 = 内存灾难），且它与 REPL 完全解耦、不在交互路径上。

## 后果

- 持久化/跨重启诉求改由 `run --file` 与 `serve --http`（独立 `RuntimeThreadStore`）承担——明确放弃"会话内任务跨重启存活"。
- **生命周期**：任务完成→空闲可续派（保留历史）→ LRU 按最近活跃度回收（保留约 5–10）；对话历史的 UI 镜像设每任务上限（~50）。
- **取消**：全局取消令牌注册表；ESC 广播取消当前前台回合 + 仍**附着**的任务，**游离**任务存活，`/task cancel <id>` 单独杀；废弃 `CancellationContext` 的 `InheritableThreadLocal` 继承（每任务显式持令牌）。
- **快照**：只锚串行主回合边界，任务写入折叠进下一个主回合快照；`revert_turn` 为 effort 级回退（非任务级外科回退）。worktree 隔离为终局增量。
- **HITL**：`requestApproval` 改为调用线程 park 在 future、由泵渲染回收；前台/后台共用一条 pump 路由审批队列，串行展示、标注请求方；"全部批准"会话级全局但授权时明示范围。
- 事件循环 + 注册表须从 `Main` 抽出为无终端依赖的可驱动单元，否则不可测（见 PRD 测试 seam）。
