# 02 — Stop 事件 Hook seam（tracer bullet）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/lifecycle-hook-seam/PRD.md`（ADR 0008）

## What to build

落 [[生命周期 Hook]] 的最薄端到端线：在 ReAct 主循环的**自然收尾路径**（LLM 不再调用工具、准备返回的分支）接入一个 `Stop` 事件点，经统一 `HookDispatcher` 分发；hook 可返回 `block` + reason，主循环据此把反馈作为内部 user message 注入下一轮并续跑（而非返回）。

契约对齐 Claude Code 公开 hook 模型，为第23期外部 shell hook 铺路：

- `HookEvent` 枚举（本轮只用 `Stop`，`PostToolUse`/`PreToolUse` 预留）
- `HookPayload`（可 JSON 序列化）
- `HookDecision`（CC 输出 schema 超集：`continue` / `decision: block|allow|deny|none` / `reason` / `feedback`）
- `Hook` 接口（`handle` + `matches` + `provenance: builtin`）
- `HookDispatcher`：每事件有序执行、第一个 block/deny 短路、聚合反馈；未注册时零开销 no-op

本切片用一个**平凡内建 hook**（如"第一次收尾 block 一次、之后放行"）证明 block→续跑闭环；真实 compile-gate 留给 issue 03。预算 / 硬轮数先到时 gate 让路（不凌驾于退出条件）。默认不注册任何 hook，交互不受影响。

可演示（测试）：脚本化 LLM 第一次收尾被平凡 hook 阻断、循环多跑一轮注入 reason、第二次收尾正常返回。

## Acceptance criteria

- [ ] `HookDispatcher` + `HookEvent`/`HookPayload`/`HookDecision`/`Hook` 落地，契约字段为 CC hook 输出 schema 超集（遵循 ADR 0008"契约现在定死"）
- [ ] 主循环自然收尾路径接 `Stop` 事件；hook 返回 block 时把 `feedback`/`reason` 作为内部 user message 注入并续跑
- [ ] 多个 hook 按确定顺序执行、第一个 block/deny 短路；未注册 hook 时 no-op、零额外开销
- [ ] 预算 / 硬轮数（`AgentBudget`）先到时 gate 不再阻拦收尾
- [ ] 权限层（[[策略守卫]] / HITL）不被改写为 hook（ADR 0008 边界）
- [ ] 测试（seam）：`HookDispatcher` 纯单元（顺序 / 短路 / 聚合 / no-op，仿 `AgentBudgetTest`）；`Agent.run()` + 脚本化 `LlmClient`（仿 `AgentLspDiagnosticsTest` 的 RecordingClient）断言 block→续跑、注入内容进入第二轮请求

## Blocked by

Issue 01（Phase 0 go/no-go 决定 seam 的首个租户 compile-gate 是否需要）
