# 03 — 活动区条带合成器：Display 驱动 + 冻结刷出 + 宽度权威统一

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`（ADR 0010 approach A）

## What to build

本轮流式块进入[[活动区]]条带渲染，diff 引擎复用 JLine `org.jline.utils.Display`（渲染线程自持实例，ADR 0010 approach A，不 fork、不 subclass）。

- 块按 `(width, foldState)` 渲染为**预折行** `AttributedString` 行并按块 memoize——行数即行高。
- 条带高度 ≤ 可视区减输入/状态保留行；内容滚出条带顶端 = **冻结刷出**到[[转写区]]（每帧一次批量，非每行）。
- 条带合成仅在 readLine 非活跃时进行；**用户输入期**后台 push 输出按帧批量 `printAbove` 追加（每帧一次而非每行一次完整 redisplay）。
- **宽度权威 = JLine 唯一**：删除 `InlineRenderer` 内两份手写宽度实现与 `estimateRows` 全家（估算算术整体消失）。
- resize（SIGWINCH）后条带按新宽度重排（memoize 按宽度失效）。
- `TerminalMarkdownRenderer` 输出从「写 PrintStream」重构为「产出样式行」；AnswerSegment 块用 append-only 行缓存：完成行渲染一次即缓存，仅尾行每帧重渲。

可演示：流式回合经条带呈现、回合结束 scrollback 内容与直写等价、无闪烁。

## Acceptance criteria

- [ ] 流式回合经条带渲染，回合结束后转写区内容恰出现一次（mock 终端捕获，零重复字节断言）
- [ ] 滚出条带顶端按帧批量冻结刷出（写出次数断言）
- [ ] 手写宽度实现与 `estimateRows` 删除；CJK 折行以 JLine `columnLength` 为准
- [ ] resize 后条带按新宽度重排（mock Terminal 尺寸变化断言）
- [ ] 输入期后台 push：每帧一次批量 `printAbove`（isReading 态断言）
- [ ] AnswerSegment 行缓存生效：风暴下仅尾行重渲（缓存命中计数器验证）

## Blocked by

- `02-single-writer-render-loop.md`
