# 02 — 移除游离的绿色答案标记

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-fixups/PRD.md`

## What to build

移除在流式答案起点打印的绿色 `answerMarker`「▪」。在类型化 inline 管线里答案经块事件渲染，这个标记与答案脱钩，落成屏幕上游离的绿色方块。答案边界改由块模型 + 统一空行表达。

若 `AnsiStyle.answerMarker` 移除后无其他使用者则一并删除。

## Acceptance criteria

- [ ] 类型化 inline 路径下答案前不再打印绿色「▪」标记
- [ ] 默认流与浏览层均无游离绿色方块
- [ ] `AnsiStyle.answerMarker` 无其他调用点则删除（若 PlainRenderer 降级路径仍需标记，保留并在说明中注明边界）
- [ ] 单测：`InlineRendererTest` 断言答案流输出不含 answerMarker 字形 / 绿色序列

## Blocked by

None - can start immediately.
