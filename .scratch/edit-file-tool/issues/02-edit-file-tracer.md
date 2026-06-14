# 02: edit_file 最小闭环（唯一匹配替换 + 注册 + diff 回显 + 集成契约）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-tool/PRD.md`

## What to build

Tracer bullet：注册 `edit_file(path, old_string, new_string, replace_all)` 工具并打通全部层——schema 出现在工具定义里 → 模型调用 → 持文件互斥锁执行 UTF-8 精确子串替换 → 落盘 → 返回"文件已编辑（替换 N 处）+ 紧凑 diff"供模型自检 → diff observer 触发行内渲染 → LSP hook 在放锁后触发（诊断经既有机制下一轮注入）。

本片只做唯一匹配 happy path（守卫矩阵在 issue 03），但必须带上两个集成契约：observer 收到 [before, after]；同批次两个不相交的同文件 edit_file 在锁下都最终落盘（spec review §1 的并发契约）。

执行细节见 plan 的 Task 1 与 Task 4。

## Acceptance criteria

- [ ] `edit_file` 出现在工具定义中，required 含 path / old_string / new_string
- [ ] 唯一匹配替换成功：磁盘内容正确，返回含"文件已编辑""替换 1 处"及 old/new diff 回显
- [ ] 整个读改写执行体持文件互斥锁（复用 issue 01 的机制，不另造）
- [ ] diff observer 收到 [before, after]，与 write_file 同一钩子
- [ ] 同批次两个不相交的同文件 edit_file：两处修改都落盘（多跑几次确定性通过）
- [ ] LSP hook 在放锁后、仅编辑成功时触发
- [ ] 单测全绿

## Blocked by

- `.scratch/edit-file-tool/issues/01-file-mutation-lock.md`
