# 04 — safe-edit：PathGuard 拒写测试目录（Guard 规则）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/lifecycle-hook-seam/PRD.md`（ADR 0008）

## What to build

在 [[策略守卫]]（PathGuard）增一条 deny 规则：写工具（`edit_file`/`write_file`）目标落在 `src/test/**`（及等价测试目录约定）时按 policy-deny 拒绝，与既有路径越界拒绝同一通道、同一审计日志。直接打 floci-248——agent 改到被 gold test_patch 覆盖的隐藏测试文件，导致打补丁冲突编不过→0/0。

这是**权限层**的拒绝，不是 hook（ADR 0008：permissions 与 hooks 分层）。仅 `run`/eval 模式启用（交互下用户合法编辑自己的测试）。路径解析复用 PathGuard 既有安全解析，不另造规范化层。

可演示（测试）：`run` 模式下让 agent 试图编辑 `src/test/...` 下既有文件→被拒、结果说明原因、磁盘不变；模式关或目录外路径→放行。

## Acceptance criteria

- [ ] PathGuard 在 `run`/eval 模式拒绝对 `src/test/**` 的 `edit_file`/`write_file`，返回明确原因，文件不变
- [ ] 拒绝走与路径越界相同的策略层与审计日志（无旁路）
- [ ] 模式门控：默认关（交互放行用户改测试）、`run`/eval 开
- [ ] 实现为 [[策略守卫]] 规则而非 [[生命周期 Hook]]（ADR 0008 边界）
- [ ] trace / 审计可见 safe-edit 拒绝次数
- [ ] 测试（seam）：`PathGuardTest` 单元 + `ToolRegistryTest` 经 `executeTool` 断言 `src/test/**` policy-deny、目录外 / 模式关放行（仿既有路径越界用例）

## Blocked by

None - can start immediately
