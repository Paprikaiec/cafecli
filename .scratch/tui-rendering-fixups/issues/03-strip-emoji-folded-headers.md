# 03 — 折叠头去 emoji，保留块色条

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-fixups/PRD.md`

## What to build

从工具调用折叠头与思考折叠头移除所有 emoji 图标，仅靠左侧 `BlockAccent` 块色条区分块类型。

- `ToolCallBlock`：`toolLabel` / `collapsedLabel` / `mcpLabel` 里的图标（🌐📰🔍📖📂⚡✏️🏗️💾🔧🔌 等）去掉；`stripPrefixIcon` 随之无用，可删。
- `ReasoningBlock`：去掉 `🧠`。

默认流与浏览层复用同一块渲染函数，两处一致生效。

## Acceptance criteria

- [ ] 工具 / 思考折叠头不含 emoji 码点（U+1F300+ / dingbats / 变体选择符 U+FE0F）
- [ ] 结构性字形保留：块色条 `▎`、引用 `│`、collapse `⏷`、braille spinner、`±`、`█░`
- [ ] 既有标签文本保留（如「读取 N 个文件」「思考 N 行」）
- [ ] 单测：`ToolCallBlock` / `ReasoningBlock` 折叠头断言无 emoji、色条完好、标签文本不变
- [ ] 文档联动（AGENTS.md §1）：更新 AGENTS.md 中被断言的头部子串（去 `🧠` 前缀）与相关测试断言

## Blocked by

None - can start immediately.
