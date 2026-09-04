# 11 — HITL 审批渲染入渲染线程：绘制/读键分离

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/tui-rendering-pipeline/PRD.md`（ADR 0011「帧调度与线程」；ADR 0005 ApprovalPump 约束沿用）

## What to build

`promptApproval` 拆分为「渲染线程绘制 + 主线程读键」：

- 审批 UI 作为[[活动区]]条带元素由渲染线程绘制（提示视觉上本就属于活动区）；主线程只做阻塞按键读取并 resolve future。
- 「读终端只在主线程」不变（AGENTS.md HITL 铁律）；队列单向（主线程→渲染线程投绘制请求）+ future 回传（审批结果），与既有 ApprovalPump 同构，结构性无死锁：渲染线程永不 block 等主线程、主线程读键时不持渲染锁。
- 语义零变化：前台回合泵审批、输入期 `printAbove` 告知 + 回车后 drain、`callerContext` 标注请求方、「全部批准」会话级全局——全部照旧。
- 子任务经 `createChildRegistry` 共享泵的行为不变。

可演示：危险工具审批提示出现在条带内，批准/拒绝/全部批准流程与现状一致；后台任务审批照旧经提示 + 回车处理。

## Acceptance criteria

- [ ] 审批 UI 由渲染线程绘制（producer 不直写），提示出现在条带
- [ ] 按键读取仍在主线程（无渲染线程读终端的路径，代码层断言/审查项）
- [ ] 前台回合泵审批与输入期 drain 语义等价（既有 HITL 测试族替换/扩展通过）
- [ ] `callerContext` 标注与「全部批准」会话级行为不变
- [ ] 死锁防线测试：审批期间渲染线程持续可渲染其它条带内容（帧泵驱动断言不阻塞）

## Blocked by

- `03-live-region-strip-compositor.md`
