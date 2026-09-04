# 05 — 弃用 /team + AgentOrchestrator；评测走 --strategy=react

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/async-task-substrate/PRD.md`（决策见 ADR 0004）

## What to build

退役确定性多 Agent 编排器（`AgentOrchestrator`）与 `/team` 入口。主 Agent 的自主编排（前台派生 + 续派）成为唯一多 Agent 模型。SWE-bench / 无人值守走 `--strategy=react`（自主自编排），不再用确定性脚手架。移除 `AgentOrchestrator` 及其测试、`/team` 命令与提示；`/plan`（`PlanExecuteAgent`）保留。

## Acceptance criteria

- [ ] 移除 `/team` 命令、`SWITCH_TEAM`、`createTeamAgent`、`AgentOrchestrator`；移除 `AgentOrchestratorTest`
- [ ] `/plan` 与 ReAct 路径不受影响
- [ ] 无人值守/SWE-bench 文档化并接通 `--strategy=react`（不启用确定性编排）
- [ ] AGENTS.md 更新：路径表 → 两条（ReAct-含编排、Plan）；模块图 + 测试矩阵同步
- [ ] 移除后全量测试通过

## Blocked by

- #01
- #02
- #04
