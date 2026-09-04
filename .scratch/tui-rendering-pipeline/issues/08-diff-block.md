# 08 — DiffBlock：diff 纳入块模型 + 全宽绿/红背景 + 能力回退

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`（可折叠种类表 + Diff 展示 stories）

## What to build

write_file / edit_file 的 diff 升级为[[可折叠块]] DiffBlock，纳入块模型（修复现状「绕过 transcript 直写终端、对折叠/重绘不可见」的问题）。

- writeFileObserver 产出 DiffBlock（保存 path/before/after 源内容），进条带与保留窗口。
- 折叠头：`± path (+N/-M)`（增删行计数）。
- 展开：hunk 组织照旧（`@@` 头青色 + 上下文行默认色），新增/删除行为**全宽绿/红背景**（行 pad 到终端宽度，对齐 Claude Code 观感）。
- 能力门控：256 色及以上用背景样式；以下回退现有前景色 `+`/`-` 风格；无裸序列泄漏。
- 既有 LCS/hunk 算法保留复用，仅渲染面升级。
- 行内变化 span 强调（更亮背景）为可选后续优化，不在本 slice。

可演示：edit_file 落盘后对话区出现 `± path (+12/-3)` 折叠头，展开为背景色 diff 块。

## Acceptance criteria

- [ ] diff 经块模型渲染（不再直写绕过），条带/浏览层/折叠均可见
- [ ] 折叠头 `± path (+N/-M)` 计数正确（新文件 / 删除 / 无变化边界）
- [ ] 展开态：add/delete 行全宽背景（字节断言背景序列 + pad），hunk 头与上下文样式正确
- [ ] 8/16 色终端回退前景色风格（能力门控断言）
- [ ] LCS/hunk 输出与既有实现等价（golden 对比）

## Blocked by

- `04-ctrl-o-in-place-toggle.md`
