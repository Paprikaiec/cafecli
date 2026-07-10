# 生命周期 Hook 先落内部 seam，可配置 shell-hook 子系统延后

> 状态：sequencing 部分被 [ADR 0012](0012-configurable-hooks-promoted-guards-absolute.md) 取代（可配置子系统提前、首租户换为读前写台账 + 收尾门）；「契约贵、租户便宜」与「permissions-vs-hooks 分层」两条结论仍有效。

SWE-bench-Live 失败归因里有两类工程可修的复发形状：补丁破坏编译直接 0/0（all-or-nothing 计分）、agent 收尾前从不自检（不编译、不跑测试）。把这两类的修复抽象出来，本质就是 Claude Code 的生命周期 hook——finish-gate ≡ `Stop`、编辑后校验 ≡ `PostToolUse`。PaiCLI 其实已有 `PreToolUse` 等价物（拦截链 HitlToolRegistry → ToolRegistry → PathGuard/CommandGuard），缺的是 `PostToolUse` 与 `Stop` 两个事件点。最终方向是对标 Claude Code 的**用户可配置 shell-hook 子系统**（见 [ROADMAP](../../ROADMAP.md) 第23期），但当前目标只需要 2 个内建守卫。

## 决策

先落**内部 `HookDispatcher` seam**：事件枚举 + `HookPayload`/`HookDecision` 契约对齐 Claude Code 公开 hook schema，本轮只接 `Stop` 事件（`Agent.java` 自然收尾路径），并以一个内建 **compile-gate**（`Stop` hook：本回合改过文件才触发、跑 harness 经 `-D` 注入的编译命令、失败则注入报错并续跑、受最大重阻次数与硬轮数兜底）落地。`PostToolUse`/`PreToolUse` 仅预留枚举与分发，不接线。

契约是贵的部分、现在定死；内建守卫是便宜、可替换的租户。可配置 shell-hook 子系统延后到第23期，届时纯增量——在同一 dispatcher 上注册 `ShellHook`、主循环不变。

新行为默认关闭、由 `run`/eval 路径开启，交互产品不受影响直到显式接入。

## 被否决的方案

- **现在就做完整可配置 shell-hook 子系统**：面太大——配置 schema、shell 执行器、以及"任意用户 shell 命令 vs CommandGuard/HITL"的安全模型，只为承载 2 个 Phase 0（`edit_file` 重基线）后可能根本不需要的守卫。内部 seam 本就是它的严格前置，先做 seam 不浪费。
- **把现有 Guard 链重写成 hook（permissions 并入 hooks）**：否决——沿用 Claude Code 的 permissions-vs-hooks 分层。`PathGuard`/`CommandGuard`/HITL 做的是允许/拒绝（权限），与做反馈/续跑的 hook 分属两层。故 **safe-edit（禁改 `src/test/**`）是一条 PathGuard 规则，不是 `PreToolUse` hook**，本轮也不接 `PreToolUse`。
- **不抽象 hook、直接硬编码两个守卫**：否决——最终要对标 Claude Code 的可配置 hook；硬编码后第23期还得拆，且失去"契约现在定死、子系统增量叠加"的前向兼容。

## 后果

- 前向兼容：契约（事件 + payload/decision）现在固定，第23期只增不改。
- 新增一条要长期维护的 Guard-vs-Hook 边界（见 [CONTEXT.md](../../CONTEXT.md)：策略守卫 vs 生命周期 Hook）。
- compile-gate 的有效性取决于 Phase 0 重基线：若外科式 `edit_file` 已基本消除 build-break，则编译校验可能是弱需求——故先 Phase 0、再按需补建内建守卫（如延后的确定性 test-gate，详见第23期前的取舍）。
