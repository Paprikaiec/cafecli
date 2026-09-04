# 01 — Ctrl+Y 切换 HITL / YOLO

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-fixups/PRD.md`

## What to build

按 `Ctrl+Y`（ASCII 25）在 HITL 与 YOLO 审批策略之间切换，空闲输入期与任务执行中都生效，切换后底部状态栏立即反映新模式。当前状态栏一直宣传「Ctrl+Y」但从无绑定。

复用 `/hitl on|off` 的同一通路（`SwitchableHitlHandler.setEnabled` + `approvalPump` 同步），抽出共享 `toggleHitl()` 供两个入口调用，避免状态分叉。空闲期为 `LineReader` widget，任务期为新增的 `RuntimeControl.TOGGLE_HITL`。

## Acceptance criteria

- [ ] 空闲输入期按 Ctrl+Y 切换 HITL↔YOLO；`classifyRuntimeControl` 对任务期 Ctrl+Y 返回 `TOGGLE_HITL`
- [ ] 切换后 `renderer.updateStatus(...)` 使状态栏在「HITL Ctrl+Y for YOLO」与「YOLO Ctrl+Y to enable HITL」间即时切换
- [ ] Ctrl+Y 与 `/hitl on|off` 走同一 `toggleHitl()`，`approvalPump` 与 `SwitchableHitlHandler` 状态保持同步（无分叉）
- [ ] 单测：`Main.classifyRuntimeControl` 对 Ctrl+Y → `TOGGLE_HITL`；`BottomStatusBar.formatStatusLine` 文案随 `hitlEnabled` 切换
- [ ] 文档联动（AGENTS.md §2 改命令入口）：README / AGENTS.md 键位说明补 Ctrl+Y

## Blocked by

None - can start immediately.
