# 03: edit_file 守卫矩阵（匹配/参数/安全边界）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-tool/PRD.md`

## What to build

给 edit_file 补全全部防盲改与参数守卫，每条失败路径返回能指导模型改对的错误文案，且文件保持不变：

- 0 匹配 → 提示与文件完全一致（含缩进/空白/换行）
- 多匹配且未开 replace_all → 报出现次数，提示补上下文或设 replace_all=true
- replace_all=true → 全部出现处替换，返回"替换 N 处"（非重叠计数）
- `old_string` 为空 → 报错并指向 write_file
- `old_string` == `new_string` → 报"无改动"
- `new_string` key 缺失 → 报"参数缺失"（不得静默解释为删除）；显式空串 → 合法删除
- 文件不存在或非普通文件 → 报错并指向 write_file
- 路径越界 → 策略拒绝（既有 PathGuard 行为）
- 编辑后内容超 5MB 上限 → 策略拒绝并记 deny 审计（与 write_file 资源上限语义一致；普通业务失败记 allow）

执行细节见 plan 的 Task 2 与 Task 3。

## Acceptance criteria

- [ ] 上述每条守卫各有单测：断言错误文案关键词 + 磁盘内容不变
- [ ] `new_string` 缺失报错、显式空串成功删除唯一匹配——两个独立用例
- [ ] replace_all 改名场景：每处出现都被替换，计数正确（含 `aaaa` 中找 `aa` 计 2 的非重叠边界）
- [ ] 超大小上限走策略拒绝路径（审计记 deny），0 匹配等业务失败记 allow
- [ ] 单测全绿

## Blocked by

- `.scratch/edit-file-tool/issues/02-edit-file-tracer.md`
