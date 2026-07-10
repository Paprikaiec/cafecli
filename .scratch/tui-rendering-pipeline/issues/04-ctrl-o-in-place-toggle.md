# 04 — Ctrl+O 原地切换：零重复重绘 + 工具调用/代码块折叠语义

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`（原始 bug 的修复演示 slice）

## What to build

Ctrl+O 命中[[活动区]]条带内输入行上方**最近**的[[可折叠块]]，由渲染线程原地重绘条带——**零追加、零 scrollback 重复**。

- 折叠种类先落两类：工具调用组（默认折叠，沿用现折叠头文案风格）、代码块（>~10 行默认折叠，短块直接展开显示）。
- 同一轮多个可折叠块可分别切换：新块出现不再冻结旧块（只要仍在条带内）。
- 滚出条带的块进入冻结态：Ctrl+O 不命中，提示「经 Ctrl+T 查看」（浏览层未落地前文案先占位）。
- `FoldableBlock` / `BlockRegistry` / `ToolCallRenderer` 的职责被块模型 + 条带吸收，随本 slice 退役删除；phase16-smoke 对应测试同步替换。

可演示：一轮内先工具调用后代码块，两者可各自展开/收起，scrollback 无任何重复内容——原始 bug 消亡。

## Acceptance criteria

- [ ] 切换在条带内原地重绘：mock 终端捕获断言该轮内容零重复字节（**原始 bug 的永久回归锚**）
- [ ] 同一轮多块分别可切换（工具调用组不因代码块后至而卡死折叠态）
- [ ] 代码块 >~10 行默认折叠、≤ 阈值默认展开；折叠头含行数与 ctrl+o 提示
- [ ] 冻结块 Ctrl+O 无效并给出 Ctrl+T 提示
- [ ] `FoldableBlock` / `BlockRegistry` / `ToolCallRenderer` 删除，替代测试落地
- [ ] Ctrl+O 键位绑定行为在 readLine 态（widget）与回合运行态均生效

## Blocked by

- `03-live-region-strip-compositor.md`
