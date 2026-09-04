# 01 — Phase 0 免费再基线与 go/no-go（HITL）

Status: ready-for-human
Type: HITL

## Parent

`.scratch/lifecycle-hook-seam/PRD.md`

## What to build

不写 Cafecli 代码：用两个零代码开关对当前 SWE-bench-Live band 做一次免费再基线，作为后续守卫的 go/no-go 依据。

- `edit_file` 开启——`ToolRegistry` 默认即注册 `edit_file`（`!editFileDisabled()`），只需用含 `edit_file` 的 jar 且**不设**禁用 flag；no-edit baseline 是被显式关掉的变体。
- `-Dcafecli.react.hard.max.iterations=80`——硬轮数默认 50（`AgentBudget`），token 预算默认无限，唯一硬墙就是轮数。

在 band 上重跑、归因 remaining fails：哪些 build-break（floci-88/299/352/grpc 类）被外科式 `edit_file` 自然消除、floci-164 是否因更高轮数翻盘。据此决定 compile-gate（issue 02/03）是否真正需要、做多深。

可演示：再基线 resolve rate 与 no-edit baseline 的 Δ，以及 build-break / 步数耗尽实例的存活情况，足以拍板 issue 02/03 是否启动。

## Acceptance criteria

- [ ] 用含 `edit_file`、未设禁用 flag 的 jar 在当前 band 跑通（trace 可确认 `edit_file` 实际被使用）
- [ ] `-Dcafecli.react.hard.max.iterations=80` 生效，floci-164 不再于 50 轮被截断
- [ ] 产出再基线 resolve rate 及与 no-edit baseline 的 Δ，按实例归因 remaining fails
- [ ] 形成 go/no-go 结论：compile-gate（issue 02/03）是否需要、是否调整范围，并写回本 issue 评论或 PRD
- [ ] 不改 Cafecli 源码（纯 harness / flag / jar 操作）

## Blocked by

None - can start immediately
