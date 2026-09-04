# 05 — 代码 / diff 内联、100 行上限、代码按宽裁剪

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-fixups/PRD.md`

## What to build

默认流里代码与 diff 内联呈现、按 100 行上限截断，超出追加一行 `… +N 行 (ctrl+o 查看全部)`。

代码块额外清理：

- 按终端宽**裁剪（尾 `…`）而非 prose 折行**——当前 `MemoizedBlock` 对代码走 `ProseEntityHighlighter.wrap` 的 `columnSplitLength` 折行，把盒绘制流程图 / 树形结构打散。裁剪保住可见部分的网格对齐。
- 移除 4 空格缩进。
- 移除 `┌─ code` / `└─ end` 边框，改用统一空行分隔进入 / 退出。

`CodeBlock` / `MemoizedBlock` 与 `TerminalMarkdownRenderer` 的围栏代码路径（`toggleCodeBlock`）同步。

## Acceptance criteria

- [ ] 超宽代码行按宽裁剪、不折行；盒绘制图形可见部分保持网格对齐
- [ ] 代码块无 `┌─` / `└─` 边框、无 4 空格缩进、用空行分隔
- [ ] 代码 / diff 超过 100 行时只出前 100 行 + 一行 `… +N 行 (ctrl+o 查看全部)` 标注
- [ ] 单测：`CodeBlock` 固定宽断言裁剪 / 行数不因折行膨胀 / 溢出标注 / 无边框；`DiffBlockTest` 断言 100 行上限 + 标注

## Blocked by

None - can start immediately.（渲染独立可验；标注里「查看全部」的展开目标由 overlay 展开切片 `07` 兑现。）
