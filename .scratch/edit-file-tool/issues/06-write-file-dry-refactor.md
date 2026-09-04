# 06: write_file 复用共享落盘助手（行为等价 DRY 重构）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-tool/PRD.md`

## What to build

write_file 与 edit_file 的"落盘 + diff observer"尾部收敛到同一个共享助手（edit_file 自诞生即使用），消除两份平行实现。行为必须严格等价：返回文案、observer 入参、新建目录、锁边界（observer 锁内 / LSP hook 锁外）都不变。

先写 characterization 测试锁定 write_file 现状，再做重构。执行细节见 plan 的 Task 7。

## Acceptance criteria

- [ ] characterization 测试先行：write_file 写入成功、observer 收到 [before, after]（重构前后均通过）
- [ ] write_file 的锁内 lambda 复用共享落盘助手，无重复的写盘/observer 代码
- [ ] LSP hook 仍在锁外、仅成功时触发
- [ ] write_file 与 edit_file 全部既有单测无回归

## Blocked by

- `.scratch/edit-file-tool/issues/02-edit-file-tracer.md`
