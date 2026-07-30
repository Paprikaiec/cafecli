# 06 — Ctrl+O 打开浏览层，退役原地折叠 + Ctrl+T，收窄活区尾

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-fixups/PRD.md`（决策见 ADR 0012）

## What to build

把折叠从「原地」彻底移到「全屏浏览层」，消解 PRD 问题 2（Ctrl+O 永远 blocked）与 3（提示追加进 scrollback）。

- `Ctrl+O` 空闲期打开[[转写浏览层]]；浏览层内再按一次 `Ctrl+O` / `q` / `ESC` 关闭、回到输入。
- `Ctrl+T` 键位退役；`/transcript` 命令保留为等价入口。
- 删除原地折叠通路：`InlineRenderer.toggleLastBlock`、`RuntimeControl.TOGGLE_FOLDABLE`、`LiveRegionStrip.toggleLastFoldable` / `frozenFoldableAvailable` / `pendingNotice`「Block frozen」。
- `LiveRegionStrip` 收窄为**活区尾**：spinner + 答案流式尾 + 审批卡 + 提交冻结；其余完成内容仍提交为冻结回卷（折叠形态）。展开态只在浏览层从[[保留窗口]]源块重建。

浏览层的「进入即全展开」与「滚动」在切片 `07` / `08` 落地；本切片先把入口从「原地 toggle」切成「打开 / 关闭浏览层」，交付一条完整可验路径。

## Acceptance criteria

- [ ] 空闲期 Ctrl+O 打开浏览层；浏览层内 Ctrl+O / q / ESC 关闭回输入
- [ ] 任何情况下不再出现「Block frozen; view via Ctrl+T」文案，也不再经 `printAbove` 追加折叠提示
- [ ] Ctrl+T 键位不再触发浏览层；`/transcript` 命令仍可打开
- [ ] 默认流仍正确：spinner、逐 token 答案尾、折叠头入 scrollback、审批卡照常渲染
- [ ] 单测：`Main.classifyRuntimeControl` 移除 `TOGGLE_FOLDABLE`；`InlineRendererTest` 断言无 notice、活区尾只含 spinner + 答案尾 + 审批卡
- [ ] 文档联动（AGENTS.md §1/§2）：更新 inline-rendering 段的 Ctrl+O / Ctrl+T 语义与命令入口说明

## Blocked by

None - can start immediately.
