# 12 — 收尾清扫 + 手工验收：文档重写、退役清点、全量回归

Status: ready-for-human
Type: HITL

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`

## What to build

管线与特性 slice 全部落地后的收尾整合：

- **AGENTS.md**「启动与 inline 渲染当前约定」段重写：活动区/转写区/保留窗口/浏览层新约定、块事件接口、Ctrl+O/Ctrl+T 键位、33ms 单写者帧循环、审批渲染新分工、`printAbove` 降级为冻结刷出机制——替换已失效的旧描述（live thinking 区自清行、BlockRegistry 冻结语义等）。
- **README**：用户可见变化（Ctrl+O 语义、Ctrl+T + `/transcript`、语法/实体高亮、OSC 8、diff 背景、`-Dcafecli.render.stats`）。
- 退役清点：`FoldableBlock` / `BlockRegistry` / `InlineActivityDisplay` / `ToolCallRenderer` / `InlineDiffRenderer` 渲染面确认删除干净，无死代码残留。
- pom `phase16-smoke` includes 更新为新测试族。
- 全量回归：`mvn test -DskipTests=false`，既有 3 红（CodeIndexTest×2 + TerminalMarkdownRenderer flaky——后者预计已随重构消亡，需确认）之外零新红。
- **手工冒烟（HITL，iTerm2 + Terminal.app）**：
  - 长思考回合流式无闪烁、~30fps 平滑；
  - Ctrl+O 多块切换无残影无重复；
  - Ctrl+T 浏览层进出 scrollback 无损、块光标导航顺手；
  - `path:line` Cmd+点击直达（iTerm2）；
  - diff 背景色正确、窄终端 resize 重排正常；
  - 审批流程（含后台任务审批）与 ESC 取消无回归。

## Acceptance criteria

- [ ] AGENTS.md inline 渲染段与 README 更新完毕（硬规则 #1）
- [ ] 退役类删除干净、无引用残留
- [ ] phase16-smoke includes 更新且通过
- [ ] 全量回归零新红（既有红名单确认）
- [ ] 手工冒烟清单逐项通过（人工签核）
- [ ] follow-up issue 已登记：审批时 diff 预览（HITL §5.4 链，复用 DiffBlock 渲染）

## Blocked by

- `06-transcript-overlay.md`
- `07-reasoning-foldable.md`
- `08-diff-block.md`
- `09-syntax-highlight-upgrade.md`
- `10-entity-highlight-osc8.md`
- `11-hitl-approval-render-thread.md`
