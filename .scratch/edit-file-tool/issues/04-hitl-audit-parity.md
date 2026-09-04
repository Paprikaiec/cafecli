# 04: edit_file 的 HITL 审批 + 审计与 write_file 对齐

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-tool/PRD.md`

## What to build

把 edit_file 纳入危险工具集，使其在 HITL 开启时与 write_file 一样先经人工审批，且每次调用（含策略拒绝）写入同一份审计日志。精确编辑不能成为绕过审批的旁路。

- 加入审批策略的危险工具集合（数量 4 → 5）
- 危险级别"中危"，风险描述说明"精确替换/修改已有文件，被替换内容将丢失"
- 加入审计工具名单，allow / policy-deny / error 语义与 write_file 一致

执行细节见 plan 的 Task 5。

## Acceptance criteria

- [ ] edit_file 需要审批：requiresApproval 为真，出现在危险工具集合中
- [ ] 危险级别为中危，风险描述非空且含"替换"语义
- [ ] 既有审批策略测试中危险工具数量断言更新为 5
- [ ] edit_file 调用写审计行；HITL 拒绝路径返回 [HITL] 文案且记 deny
- [ ] 单测全绿

## Blocked by

- `.scratch/edit-file-tool/issues/02-edit-file-tracer.md`
