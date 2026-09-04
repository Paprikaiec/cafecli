# 03 — send_message 续派 + task_output 检视

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/async-task-substrate/PRD.md`

## What to build

任务跑完后进入**空闲-可续派**（历史保留在注册表）。新增 `send_message({to: task_id, message})` 在保留历史上恢复一个空闲任务（带综合后的后续指令续派）。新增 `task_output({task_id})` 按需拉取任务完整轨迹（默认交付仍 result-only）。对未知/已回收 `task_id` 续派或检视，返回明确的"已过期，请重开"。

可演示：派生一个研究任务，再 `send_message` 续派它执行一个复用其已加载上下文的实现指令；需要时 `task_output` 展示完整轨迹。

## Acceptance criteria

- [ ] 完成的任务转为空闲态（历史保留、无运行线程）
- [ ] `send_message({to, message})` 在保留历史上恢复空闲任务并返回新结果
- [ ] `task_output({task_id})` 按需返回任务完整轨迹；默认完成交付仍为 result-only
- [ ] 对未知/已回收 id 的 `send_message`/`task_output` 返回"已过期，请重开"（不报错、不崩）
- [ ] 测试（桩 `LlmClient`）：续派复用既有上下文（断言续派轮看到保留历史）；已回收 id 路径返回过期提示

## Blocked by

- #01
