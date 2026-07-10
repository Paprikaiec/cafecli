# 09 — ESC 附着/游离取消 + /task cancel

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/async-task-substrate/PRD.md`

## What to build

建立取消令牌注册表，持有当前主回合令牌与每任务令牌。ESC **广播取消**当前前台回合及所有仍**附着**的任务；**游离**任务（显式后台、或超时转后台）不受 ESC 影响。`/task cancel <id>` 取消单个任务（其令牌 + 中断其虚拟线程）。废弃 `InheritableThreadLocal` 继承——每任务持有自己的令牌。

可演示：派生一个后台（游离）任务；开一个前台回合；按 ESC → 前台停、游离任务继续；`/task cancel <id>` 停掉那个游离任务。

## Acceptance criteria

- [ ] 每任务与每主回合的取消令牌在注册表中追踪；任务不再经 `InheritableThreadLocal` 继承令牌
- [ ] ESC 取消当前前台回合 + 所有附着任务；游离任务不受影响
- [ ] `/task cancel <id>` 恰好取消该任务（令牌 + 线程中断）并标记终止
- [ ] 测试（纯）：给定注册表状态，断言恰好当前回合 + 附着令牌被取消、游离存活

## Blocked by

- #07
