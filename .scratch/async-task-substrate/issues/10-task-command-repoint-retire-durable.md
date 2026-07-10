# 10 — /task 重指向注册表 + 退役 DurableTaskManager

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/async-task-substrate/PRD.md`（决策见 ADR 0005）

## What to build

把 `/task` 命令重指向内存 `TaskRegistry`：列出当前任务及状态、检视单个任务、取消单个任务（复用 #09）。退役 `DurableTaskManager` / `DurableTask` / `TaskCommandFormatter` 及旧的 SQLite 版 `/task add/log/cancel`。持久化/headless 诉求仍由 `run --file` 与 `serve --http`（`RuntimeThreadStore`，不动）承担。

可演示：`/task` 列出会话内运行/空闲任务；`/task cancel` 杀掉一个；重启 PaiCLI 后无持久化任务队列。

## Acceptance criteria

- [ ] `/task list` 显示会话内任务（运行/空闲）及状态；`/task <id>` 检视；`/task cancel <id>` 取消（经 #09）
- [ ] 移除 `DurableTaskManager`、`DurableTask`、`TaskCommandFormatter`；移除 `DurableTaskManagerTest`
- [ ] `serve --http`（`RuntimeThreadStore`）与 `run --file` 路径不受影响
- [ ] `/task` 不再读写 SQLite；无跨重启任务持久化
- [ ] AGENTS.md `/task` 提示更新

## Blocked by

- #07
- #09
