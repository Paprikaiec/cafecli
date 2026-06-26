# 05 — self-check prompt-nudge（系统提示软引导）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/lifecycle-hook-seam/PRD.md`（ADR 0008）

## What to build

在系统提示加一句引导：收尾前先写最小复现并跑一下自检（用既有 `execute_command`）。软性补"从不自检"失败形状的廉价一半（#3a）——agent 手里有工具却常忘了用。仅 `run`/eval 注入，交互不注入（不撑大交互系统提示）。

这是 prompt 改动、不是 hook、不进 `HookDispatcher`；确定性 test-gate 延后到 Phase 0 后再议（见 PRD Out of Scope）。

可演示（测试）：`run`/eval 开关下组装的系统提示含该引导；关时不含。

## Acceptance criteria

- [ ] 系统提示在 `run`/eval 模式包含收尾自检引导（写最小复现并跑）
- [ ] 交互模式不注入该引导
- [ ] 不引入 hook / 不改 `HookDispatcher`（纯 prompt 层）
- [ ] 测试（seam）：`PromptAssemblerTest` 断言引导在开关开时出现、关时不出现（仿 `SpawnPromptDisciplineTest`）

## Blocked by

None - can start immediately
