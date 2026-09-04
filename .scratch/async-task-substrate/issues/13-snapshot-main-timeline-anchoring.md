# 13 — 快照锚定主回合时间线（并发下）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/async-task-substrate/PRD.md`（决策见 ADR 0005）

## What to build

让 Side-Git 快照在并发任务下保持连贯：pre/post 快照**只锚定串行主回合边界**（前台回合 + push 回合，都在执行器上）。任务不单独快照；其写入折叠进下一个主回合快照。`revert_turn` 回退工作区到某主回合边界（**effort 级**粗回退——撤销该边界以来 主 + 折叠进来的任务写入），非任务级外科回退。单文件写原子性（[[文件互斥锁]]）约束撕裂捕获。

可演示：有后台任务在写文件时，`revert_turn` 干净地把工作区回退到某个选定主回合之前。

## Acceptance criteria

- [ ] 快照只在串行主回合边界触发（前台 + push）；任务不做单独快照
- [ ] 完成任务的写入被下一个主回合快照（push 回合或下一个前台回合）捕获，无论它是否 push
- [ ] `revert_turn` 回退到某主回合边界（effort 级），连带该边界以来折叠进的任务写入
- [ ] 快照内无半写文件（经锁的单文件原子写）；时间线保持线性
- [ ] 测试（桩 seam）：驱动主回合 + 模拟任务写入；断言快照边界对齐主回合、revert 恢复到预期工作区状态

## Blocked by

- #07
