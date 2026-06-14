# 01: 文件互斥锁落地（write_file 持锁 + 提交链并发测试）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-tool/PRD.md`

## What to build

实现 ADR 0001 的文件互斥锁（File Mutation Lock）并让 `write_file` 持锁：同文件写串行排队、异文件照常并行。锁表为 JVM 进程级静态；锁 key 用 PathGuard 安全路径解析的返回值；等锁用可中断锁、获锁后复检取消（杜绝批次超时后的幽灵写入）；锁加在工具 executor 内部（HITL 之后）；临界区 = 读 before → 写盘 → diff observer，LSP hook 在放锁后且仅成功时触发。

端到端可演示：同一工具批次内两个 `write_file` 打同一文件，落盘串行、diff 事件按提交顺序发出、终态为后提交者的完整内容——不再丢失更新。

执行细节见 plan 的 Task 0（含完整 TDD 步骤与代码）；并发取舍不重议，见 ADR 0001。

## Acceptance criteria

- [ ] 同批次两个 `write_file` 写同一文件：observer 提交链成立（第二次写的 before == 第一次写的 after），终态等于后提交者全量内容；断言对调度顺序不敏感，多跑几次确定性通过
- [ ] 等锁使用可中断锁；获锁后复检取消标志才开始读改写（实现审查项）
- [ ] diff observer 在锁内触发；LSP hook 在放锁后、仅写入成功时触发（实现审查项）
- [ ] 锁 key 来自 PathGuard 安全路径解析（同一文件的不同写法路径归一到同一把锁）
- [ ] write_file 既有行为（返回文案、observer 入参、新建目录）无回归，相关单测全绿

## Blocked by

None - can start immediately
