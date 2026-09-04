# 11 — HITL 走泵路由 + 后台审批 + 全部批准范围披露

Status: ready-for-human
Type: HITL（审批即输入态 + 安全敏感的 approve-all）

## Parent

`.scratch/async-task-substrate/PRD.md`（决策见 ADR 0005）

## What to build

把 HITL 审批改为经事件循环输入泵路由，而非在工具线程上读 stdin：调用（虚拟）线程 **park 在一个 future** 上，主线程渲染审批提示并 resolve。前台回合与后台任务**共用一条串行审批队列**（同一时刻一个提示），每条请求**标注请求方**（主 Agent / `task_id`+标签）。后台任务的审批**上浮给用户**（不静默拒绝）。"全部批准"保持**会话级全局**（对所有 agent 生效），但提示**明示范围**（"本会话所有 agent 生效"），措辞按危险等级（🔴/🟡）缩放。派生任务权限**不可超过其派生者**。

可演示：一个后台实现任务请求 `edit_file` 审批；提示带任务名出现并说明会话级范围；"全部批准"后该工具对所有 agent 不再提示。

## Acceptance criteria

- [ ] `requestApproval` 不再在调用线程读 stdin；调用方 park 在由泵 resolve 的 future 上
- [ ] 前台 + 后台审批共用一条串行队列；并发请求不在屏幕上交错；每条标注请求方
- [ ] 后台任务审批上浮给用户；不因脱离主线程而静默自动拒绝
- [ ] "全部批准"会话级全局，但提示披露范围（"所有 agent，本会话"）；措辞按 `ApprovalPolicy` 危险等级缩放
- [ ] 任务被授予的权限不可超过其派生者所持有的
- [ ] 测试：双线程 + latch 证明调用方 park 直到决定被投递；断言请求方标注与"全部批准"跨 agent 生效

## Blocked by

- #07
