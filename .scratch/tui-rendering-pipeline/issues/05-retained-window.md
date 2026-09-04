# 05 — 保留窗口：最近 5 用户回合的可重渲染模型 + /clear 语义

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`（CONTEXT.md [[保留窗口]]）

## What to build

[[保留窗口]]落地：渲染器在内存保留最近 **5 个用户回合**的 `Turn → List<Block>` 模型（用户输入 + 该轮全部产出块），跨回合保留、超出滚动淘汰。

- `beginTurn` 不再清空 transcript 模型（现状每轮清空是 `/task` 输出在重绘中消失的原因），改为回合入窗、最旧出窗。
- 淘汰时释放对应块的渲染行缓存，内存有界。
- `/clear` 清空保留窗口 + 全部折叠状态（既有语义不变：conversationHistory、shortTermMemory、Skill buffer、main scope 结果照旧清理）。
- 本 slice 只建模型与生命周期；消费方（转写浏览层）在后续 slice。

可演示：多轮对话后模型内恰保留最近 5 回合；`/clear` 后窗口为空。

## Acceptance criteria

- [ ] 5 回合保留，第 6 回合进入时最旧回合淘汰（模型断言）
- [ ] 淘汰释放块行缓存（内存有界，计数器或引用断言）
- [ ] `beginTurn` 不清空窗口；多回合后条带/冻结刷出行为零回归
- [ ] `/clear` 后保留窗口为空、折叠状态复位
- [ ] 后台 push 自治回合同样入窗（事件循环路径）

## Blocked by

- `03-live-region-strip-compositor.md`
