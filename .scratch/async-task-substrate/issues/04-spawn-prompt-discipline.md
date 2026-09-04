# 04 — Prompt 纪律（continue-vs-spawn + 绝不下放理解）

Status: ready-for-agent
Type: AFK

## Parent

`.scratch/async-task-substrate/PRD.md`

## What to build

把委派纪律写进主 Agent 系统提示：(1) 把子 Agent 的研究发现综合成带文件路径/行号的**具体指令**，绝不写"基于你的发现……"；(2) 按上下文重叠度选择**续派 vs 重开**；(3) 任务通知是信号、不是用户——把结果总结给用户，不要去"感谢"任务。行为层面：主 Agent 写自包含的子 Agent prompt，并有意识地选择续派/重开。

## Acceptance criteria

- [ ] 主 Agent 系统提示包含 continue-vs-spawn 选择指引与"绝不下放理解"规则
- [ ] 提示明确任务通知是需向用户转述的内部信号（非对话方）
- [ ] 该纪律出现在主 Agent（及任何可派生路径）的已组装提示中，由 prompt 组装测试断言相应段落存在

## Blocked by

- #01
- #03
