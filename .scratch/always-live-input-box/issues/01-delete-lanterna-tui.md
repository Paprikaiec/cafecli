# 01 — 删除 Lanterna 全屏 TUI

Status: ready-for-agent

## Parent

`.scratch/always-live-input-box/PRD.md`（ADR 0015）

## What to build

移除 `CAFECLI_TUI=true` 后面那套 Lanterna 全屏 TUI。它是 Phase 16 的可选备用前端，与 inline 渲染器重复实现同一交互模型，且是 `PlanExecuteAgent` 的第二个消费者；ADR 0015 判定不保留、不迁移。

删除 `tui/` 整包及其测试，摘掉 CLI 启动路径里的 TUI 分支判断，让渲染器选型收敛——`lanterna` 模式本就已经退化成返回 `PlainRenderer`，该分支与 `PAICLI_TUI` 兼容告警一并收敛。`phase16-smoke` profile 中指向 TUI 引导测试的条目须摘除，profile 本身保留（后续 wave 仍用它跑 TUI 回归）。

用户可见变化：设置 `CAFECLI_TUI=true` / `CAFECLI_RENDERER=lanterna` 不再进入全屏 TUI。`CAFECLI_RENDERER=plain` 仍是有效的降级逃生口。

## Acceptance criteria

- [ ] `tui/` 包及其全部测试不再存在，`mvn clean package` 通过
- [ ] CLI 启动路径不再引用 TUI 引导；设置 `CAFECLI_TUI=true` 启动进入正常 inline 交互而非全屏 TUI
- [ ] 渲染器选型不再暴露 lanterna 模式；`CAFECLI_RENDERER=lanterna` 给出可理解的提示并回退，不抛异常
- [ ] `CAFECLI_RENDERER=plain` 行为不变
- [ ] `phase16-smoke` profile 不再引用已删除的测试，且该 profile 可正常运行
- [ ] `mvn test -Pquick` 全绿
- [ ] 文档同步：README / ROADMAP / AGENTS.md 中关于 Lanterna TUI 与 `CAFECLI_TUI` 的描述移除或标注为已退役

## Blocked by

None - can start immediately
