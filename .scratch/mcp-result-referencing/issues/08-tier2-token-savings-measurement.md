# 08 — Tier-2 2 臂 token 降幅度量

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/mcp-result-referencing/PRD.md`（ADR 0009）

## What to build

产出本 feature 的简历级指标：在一个仿真调试场景上做 2 臂前后对照，量化热上下文 token 降幅并验证根因字段可恢复。

- Fixture：Chrome DevTools checkout-regression 场景，经 `InMemoryTransport` 依次返回大 DOM snapshot、数百条 network request、大响应体、trace insight、Lighthouse report path；**根因字段刻意置于 digest 取样之外**（如某条深层 network request 的 `requestId`/`traceId`/error code）。
- 2 臂：raw-inline 基线 vs Option A（引用化）。度量各臂热上下文 input-token（或字符）总量，以及根因字段是否可恢复。
- 断言：Option A 相对基线热上下文降幅 ≥ 一个保守下界；根因字段经 `jq`（或 `grep_code`+`read_file`）可恢复。产出可复核的降幅数字（供报告/简历）。

可演示（测试）：度量测试跑两臂、打印并断言降幅 + 根因字段可恢复。

## Acceptance criteria

- [ ] checkout-regression fixture（多类大结果、根因字段在样本外），经 `InMemoryTransport` 驱动
- [ ] 2 臂对照：raw-inline vs Option A，度量热上下文 token/字符量
- [ ] 断言：Option A 热上下文降幅 ≥ 保守下界；根因字段可恢复
- [ ] 产出可复核降幅数字
- [ ] 测试（seam）：`InMemoryTransport` 度量测试

## Blocked by

Issue 02（引用化 tracer bullet）、Issue 03（富 digest）、Issue 04（引导恢复）
