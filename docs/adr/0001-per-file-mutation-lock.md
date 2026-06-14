# 文件写入并发安全采用按文件互斥锁，而非全局写串行

工具批次默认并行执行（上限 4 线程），同一批两个写工具打同一文件会产生 last-writer-wins 丢失更新。我们决定采用 pi agent 式的**按文件互斥锁**作为唯一正典机制：`write_file` / `edit_file` 的整个读改写执行体持锁，同文件串行、异文件照常并行。曾考虑的"读写分批"（批内出现写工具则全局串行）方案废弃——它牺牲并行度、且需要给 Tool record 加 readOnly 元数据，粒度不必要地粗。

关键取舍：

- **锁表为 JVM 进程级静态**（而非 ToolRegistry 实例字段）：交互会话与 DurableTaskManager 后台任务各持独立 ToolRegistry 指向同一项目（`Main.runHeadlessTask`），实例级锁罩不住这条已知并发路径。跨进程互斥（OS 文件锁）明确不做，与 pi 对齐。
- **锁 key 用 `PathGuard.resolveSafe` 的返回值**：它已做"最近存在祖先 toRealPath + 剩余段拼回"，等价 pi 的 realpath 回退，无需再造规范化层。
- **只锁写工具**（write_file/edit_file）：read_file 不锁（读到旧内容不破坏正确性，下轮自纠正）；create_project 目标为全新目录、碰撞风险≈0；revert_turn 需要的是全局锁语义，另行考虑。
- **等锁用 `lockInterruptibly` + 获锁后复检取消**：`executeTools` 的 invokeAll 批次超时会 interrupt 未完成任务，不可中断的 `lock()` 会造成"幽灵写入"（用户已收到超时、文件随后仍被改）。持锁者不受影响，锁保持到当前操作 settle。
- **锁加在 executor 内部**（HITL 审批之后），不会持文件锁等待人工批准；锁表条目不清理，上限为进程内碰过的不同文件数。

固有限制（接受）：批内兄弟 tool call 互相看不到结果，第二个同文件编辑的 old_string 基于旧内容生成；锁只保证串行落盘不丢更新，相交编辑会因唯一匹配失败而明确报错，由模型重试。
