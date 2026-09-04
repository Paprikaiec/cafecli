# 07: SWE-bench A/B 开关 + 全量回归收尾

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-tool/PRD.md`

## What to build

为 SWE-bench A/B 提供可复现的 baseline 臂：一个系统属性开关把 edit_file 从工具注册表整体移除（含 schema，模型完全看不到，而非仅 prompt 不提）。默认（treatment 臂）edit_file 正常注册。

本片同时承担整个 feature 的收尾验证：全量回归 + 可执行 jar 构建 + 手工冒烟（行内 diff 显示、开关生效）。

执行细节见 plan 的 Task 8 与 Task 9。trace 归因统计（edit_file vs write_file 占比、截断次数）在 eval 侧，不在本片范围。

## Acceptance criteria

- [ ] 默认注册 edit_file；设置禁用系统属性后新建的注册表不含 edit_file（schema 中也不出现）
- [ ] 开关有单测覆盖（设置/清理属性，互不污染）
- [ ] 快速回归 profile 全绿；与 edit_file/write_file/审批策略相关的失败必须修复，无关的既有失败如实记录
- [ ] 可执行 jar 构建成功
- [ ] 手工冒烟：edit_file 调用显示行内 diff；禁用开关后工具不可见

## Blocked by

- `.scratch/edit-file-tool/issues/02-edit-file-tracer.md`
