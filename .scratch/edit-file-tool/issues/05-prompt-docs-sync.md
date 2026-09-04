# 05: Prompt 引导 + 文档同步

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/edit-file-tool/PRD.md`

## What to build

让模型默认选对工具，并把工具清单文档同步到 12 个：

- edit_file 工具描述：唯一匹配要求、replace_all 用法、空串删除、省 token 不误伤，以及并发引导句"同一批次的多个 edit_file 看不到彼此的修改；同一文件多处修改请分轮顺序进行"
- write_file 描述追加"对已有文件的局部修改请优先 edit_file"
- agent 模式系统提示加一行同义引导
- AGENTS.md 核心内置工具数 11 → 12，edit_file 列在 write_file 旁

范围按 PRD：仅 agent 模式提示词与 AGENTS.md；plan/team-worker 模式提示词及 README、agents-reference 的扩展联动在 Out of Scope（待单独裁决）。执行细节见 plan 的 Task 6。

## Acceptance criteria

- [ ] 工具定义断言：edit_file 描述含"唯一"与"replace_all"与分轮顺序引导；write_file 描述提及 edit_file
- [ ] agent 模式提示词含 edit_file 优先引导
- [ ] AGENTS.md 工具数与清单更新
- [ ] 单测全绿

## Blocked by

- `.scratch/edit-file-tool/issues/02-edit-file-tracer.md`
