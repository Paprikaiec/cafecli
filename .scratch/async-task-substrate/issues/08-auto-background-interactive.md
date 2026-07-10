# 08 — 前台超时自动转后台（仅交互，~2min）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/async-task-substrate/PRD.md`

## What to build

**交互模式**下，前台派生运行超过可配置阈值（~2min）即**自动转后台（detached）**：工具返回"已转入后台 — task_id X"（解除本轮/批次阻塞），任务继续跑、稍后 push 结果。**headless 模式关闭**该行为——前台派生无限阻塞（无事件循环、无 push）。

可演示：交互模式下一个长前台子 Agent 超阈值后转后台、稍后 push 结果；headless 下它只是阻塞到完成。

## Acceptance criteria

- [ ] 交互：前台任务超阈值转为 detached；前台工具调用返回带 `task_id` 的"已转入后台"结果
- [ ] 转后台的任务稍后完成并经事件循环 push 结果
- [ ] headless：auto-background 关闭，前台派生阻塞到完成
- [ ] 阈值可配置（`paicli.*` 属性），默认 ~2min
- [ ] 测试：用延迟超过小测试阈值的桩，断言前台调用返回"已转入后台"且稍后 push 交付结果；断言 headless 路径改为阻塞

## Blocked by

- #07
