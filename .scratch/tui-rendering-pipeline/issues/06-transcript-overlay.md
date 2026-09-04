# 06 — 转写浏览层：Ctrl+T + /transcript 全屏 pager（块光标导航）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`（CONTEXT.md [[转写浏览层]]）

## What to build

[[转写浏览层]]：全屏 overlay 渲染[[保留窗口]]（最近 5 回合），支持逐块折叠/展开——已冻结滚出的块由此重新可切换。

- 入口：**Ctrl+T** 键位 + **`/transcript`** 斜杠命令。命令按 AGENTS.md 硬规则 #2 联动：Main + CliCommandParser + 测试 + README + AGENTS.md；未知命令仍 CLI 层报错不回退 Agent。
- 渲染：`Display(terminal, fullScreen=true)`（JLine 全屏 diff 模式），alternate screen **仅用于 overlay**；退出后主屏与 scrollback 与进入前完全一致。
- 导航：`j/k`/方向键移动**块光标**（视口跟随），`Enter`/`Ctrl+O` 切换选中块折叠态，`PgUp/PgDn/Space` 翻页，`g/G` 首尾，`q`/`ESC` 退出。
- model/key 分离：光标移动与折叠状态迁移为纯函数（先例：PlanReviewInputParser 与 raw 循环分离），按键循环薄壳、读键在主线程。
- 仅空闲提示符可进：回合运行中 Ctrl+T 打一行提示（空闲后可用），不进 overlay。

可演示：展开三轮前的某个工具调用 = Ctrl+T → j/k 定位 → Enter，退出后终端历史无损。

## Acceptance criteria

- [ ] Ctrl+T 与 `/transcript` 均可进入；回合运行中仅提示不进入
- [ ] 块光标导航 + 切换 + 翻页 + 首尾 + 退出全键位生效（纯函数状态迁移测试覆盖）
- [ ] overlay 内折叠切换对保留窗口模型生效（退出后再进状态保持）
- [ ] 退出后主屏 scrollback 无损（alt-screen enter/leave 字节断言）
- [ ] 命令联动完成：CliCommandParser 测试 + README + AGENTS.md 更新
- [ ] overlay 渲染复用 `Block.render(width, foldState)`（与条带同一模型）

## Blocked by

- `04-ctrl-o-in-place-toggle.md`
- `05-retained-window.md`
