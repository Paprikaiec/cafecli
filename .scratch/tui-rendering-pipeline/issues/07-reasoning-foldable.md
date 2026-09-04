# 07 — 思考引用可折叠：回合结束折叠为一行摘要

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`（可折叠种类表）

## What to build

Reasoning 块成为[[可折叠块]]：流式期间 thinking 活动区行为不变（spinner + live 预览照旧）；回合内该段收尾（content 开始 / 迭代切换）后，完整 reasoning 引用块以**折叠态一行摘要**落入条带（如 `🧠 思考 N 行 (ctrl+o to expand)`），可经 Ctrl+O 原地展开或在转写浏览层切换。

- 「补充思考」（late reasoning）同样按 Reasoning 块落条带，同一折叠语义。
- 展开态渲染沿用现有灰色竖线引用样式。

可演示：长思考回合结束后正文区干净（一行摘要），Ctrl+O 展开完整思考过程，零重复输出。

## Acceptance criteria

- [ ] reasoning 收尾后落条带为一行折叠摘要（含行数），默认折叠
- [ ] Ctrl+O 原地展开/收起，零重复字节
- [ ] 转写浏览层内可切换（滚出后）
- [ ] 流式期间 thinking 活动区行为不回归（live 预览照旧）
- [ ] late reasoning（补充思考）同折叠语义
- [ ] PlainRenderer / headless 路径不折叠、线性输出照旧（default 行为）

## Blocked by

- `04-ctrl-o-in-place-toggle.md`
