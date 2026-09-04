# 04 — 统一空行、删除分隔线

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-fixups/PRD.md`

## What to build

`TerminalMarkdownRenderer` 排版归一，去掉横线切割段落，改用统一空行分块：

- 删除标题下划线（`renderHeading` 的 `=` / `-` 重复行）——标题只保留加粗 / 着色文本。
- 识别 `---` / `***` / `___` 主题分隔线并渲染为单空行，不再逐字打印。
- 统一块间距为**恰好一个空行**；块内、列表项之间、引用内不额外空行（`ensureBlockSpacing` / `writeBlankLine` / `needsLineBreakBeforeNextBlock` 归一）。
- 与简化后的回卷空行协调，避免块之间出现双重空行。

## Acceptance criteria

- [ ] 标题渲染无 `====` / `----` 下划线行
- [ ] `---` / `***` / `___` 渲染为单空行
- [ ] 相邻顶层块之间恰好一个空行；列表项之间无多余空行；无莫名其妙的大段空行
- [ ] 单测：`TerminalMarkdownRenderer` 输出行序列断言上述规则（无下划线行、分隔线→空行、块间恰一空行）

## Blocked by

None - can start immediately.
