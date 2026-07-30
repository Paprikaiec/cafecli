# 07 — 浏览层进入即全展开

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-fixups/PRD.md`（决策见 ADR 0012）

## What to build

Ctrl+O 打开的[[转写浏览层]]进入即把[[保留窗口]]最近 5 轮的所有折叠块（思考 / 工具 / 代码 / diff）**强制展开**渲染，复用与默认流相同的块渲染函数（`foldState=EXPANDED`）——浏览层即默认流内容的展开，风格天然一致，无视觉断层。

`TranscriptOverlayModel` 初始态改为全展开；原按块光标的单块 toggle 语义改为整体全展开（或移除单块 toggle）。

## Acceptance criteria

- [ ] Ctrl+O 打开时所有折叠块默认展开（不再是折叠 + 单块 toggle）
- [ ] 浏览层块渲染与默认流一致：同一渲染函数，仅 `foldState` 不同
- [ ] 作用域为最近 5 个用户回合（保留窗口）
- [ ] 单测：`TranscriptOverlayModel` 断言进入态所有可折叠块为 `EXPANDED`

## Blocked by

- 切片 `06`（Ctrl+O 打开浏览层、退役原地折叠）
