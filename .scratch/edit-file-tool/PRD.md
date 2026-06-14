# PRD: 文件互斥锁 + edit_file 精确编辑工具

Status: ready-for-agent
Date: 2026-06-12
Branch: feat/edit-file-tool
Decision record: ADR 0001（按文件互斥锁）

## Problem Statement

PaiCLI 改代码目前只有整文件 `write_file`，没有局部编辑原语。在 SWE-bench 这类"改大仓库里某文件的几行"场景中，整文件重写烧 output token、容易幻觉/丢代码、且会撞 max_output_tokens 截断导致补丁无声作废。

同时，工具批次默认并行执行（同一条 assistant 消息里的多个 tool call 并发跑，上限 4 线程），而文件写入工具没有任何并发保护：同一批两个写工具打同一文件时，各自读到同一份旧内容、各自计算修改、后落盘者覆盖前者——丢失更新。引入 edit_file 后同文件多编辑会成为高频模式，这个坑会被频繁踩中。

## Solution

参照 pi agent 的方案分两层解决：

1. **文件互斥锁（File Mutation Lock）**：写工具的整个读改写执行体持按文件粒度的互斥锁——同文件写串行排队，异文件照常并行。这是文件写入并发安全的唯一正典机制（原"读写分批"全局串行方案废弃）。
2. **`edit_file` 工具**：`old_string → new_string` 精确子串替换，唯一匹配保证编辑不落错位置，`replace_all` 支持改名场景，空 `new_string` 表示删除。比 `write_file` 省 token、不误伤无关代码。

锁先行落地（edit_file 从诞生起持锁，无裸奔窗口期），edit_file 与 write_file 在审计、HITL 审批、行内 diff 渲染、LSP 诊断闭环上完全对齐。

## User Stories

1. As a PaiCLI 用户, I want Agent 改已有文件时只替换目标片段而不是重写整个文件, so that 不会因模型重写长文件时幻觉/丢代码而损坏我的仓库。
2. As a PaiCLI 用户, I want 精确编辑比整文件重写消耗少得多的 output token, so that 同样预算下 Agent 能完成更多轮修改。
3. As an LLM agent, I want `old_string` 不唯一时得到明确报错（出现次数 + 怎么改对）, so that 我能补充上下文重试而不是把改动落到错误位置。
4. As an LLM agent, I want 0 匹配时得到"请确认与文件完全一致（含缩进/空白/换行）"的报错, so that 我知道是匹配问题而不是文件不存在。
5. As an LLM agent, I want 用 `replace_all=true` 一次替换所有出现处, so that 改名/批量替换不用逐处发调用。
6. As an LLM agent, I want 显式传空 `new_string` 来删除匹配内容, so that 删除代码是一个明确表达的合法操作。
7. As an LLM agent, I want 漏传 `new_string` 时收到"参数缺失"报错而不是被解释为删除, so that 我的漏参不会静默删掉用户代码。
8. As an LLM agent, I want 编辑不存在的文件时被指向 `write_file`, so that 我能立即用正确的工具新建文件。
9. As an LLM agent, I want 成功结果里带紧凑 diff 回显, so that 我能在结果里自检改动是否符合意图。
10. As a PaiCLI 用户, I want 同一批次两个写工具打同一文件时两个修改都最终落盘（或第二个明确失败）, so that 并行模式不会静默丢失我的更新。
11. As a PaiCLI 用户, I want 不同文件的写入保持并行, so that 并发安全不以牺牲多文件批量修改的速度为代价。
12. As a PaiCLI 用户, I want 工具批次超时后被取消的写操作绝不再落盘（无幽灵写入）, so that 我看到的执行结果与磁盘状态一致。
13. As a PaiCLI 用户, I want 按 ESC 取消任务后，还在等锁的写操作放弃执行, so that 取消是即时且彻底的。
14. As a PaiCLI 用户, I want 交互会话和后台 durable task 同时写同一文件时也被互斥保护, so that 后台任务不会和我正在进行的会话互相踩踏。
15. As a PaiCLI 用户（HITL 开启）, I want `edit_file` 和 `write_file` 一样先经我审批, so that 精确编辑不会成为绕过人工审批的旁路。
16. As a 安全审计者, I want `edit_file` 调用与策略拒绝（路径越界、超大小上限）写入与 `write_file` 同一份审计日志, so that 写操作的安全边界可解释且无遗漏。
17. As a PaiCLI 用户, I want 每次精确编辑在终端显示 git 风格行内 diff, so that 我能实时看到 Agent 到底改了什么。
18. As a TUI 用户, I want 同文件连续编辑的 diff 按提交顺序渲染, so that 我看到的变更历史与磁盘实际演进一致。
19. As a PaiCLI 用户, I want 编辑后的 LSP 诊断在下一轮注入对话, so that Agent 能自纠正它刚引入的编译错误。
20. As an LLM agent, I want 工具描述告诉我"同一批次的多个 edit_file 看不到彼此的修改，同文件多处修改请分轮顺序进行", so that 我能主动规避并行模式的固有限制、减少失败重试。
21. As an LLM agent, I want `write_file` 描述和系统提示引导我"改已有文件优先 edit_file", so that 我默认选择更安全省钱的编辑方式。
22. As a SWE-bench 评测工程师, I want 一个开关在 baseline 臂完全移除 `edit_file`（不只是 prompt 不提）, so that A/B 对比可复现、resolve rate Δ 可归因。
23. As a SWE-bench 评测工程师, I want 从 trace 区分 edit_file 与 write_file 的使用占比和截断次数, so that 我能解释"为什么涨/没涨"。
24. As a 后续贡献者, I want 并发取舍记录在 ADR 里、术语收进 CONTEXT.md, so that 我不会好心把"缺失的 readOnly 元数据"或"全局写串行"重新发明回来。

## Implementation Decisions

**并发（ADR 0001，已定不重议）**

- 文件互斥锁是唯一正典机制；"读写分批 / readOnly 工具元数据"方案废弃，不得实现。
- 锁表为 JVM 进程级静态（独立小类），原因：交互会话与后台 durable task 各持独立 ToolRegistry 指向同一项目，实例级锁罩不住。跨进程 OS 文件锁明确不做。
- 锁 key 使用 PathGuard 的安全路径解析返回值（已含"最近存在祖先 realpath + 剩余段拼回"，等价 pi 的 realpath 回退），不另造规范化层。
- 等锁用可中断锁 + 获锁后复检取消：批次超时的 interrupt 必须让等锁任务立即放弃（杜绝幽灵写入）；持锁者不受影响，锁保持到当前操作 settle。
- 锁加在工具 executor 内部（HITL 审批之后），不会持文件锁等待人工批准。
- 临界区 = 读 before → 写盘 → diff observer；observer 在锁内（diff 事件按提交顺序发出）；LSP hook 在放锁后执行（同步编译不拉长临界区），且仅在编辑成功后触发。
- 锁表条目不清理，上限为进程内碰过的不同文件数。
- 落地顺序：锁作为前置任务先行，write_file 先持锁，edit_file 从诞生起持锁。

**edit_file 语义**

- 参数：`path` / `old_string` / `new_string` / `replace_all`（默认 false）。
- UTF-8 文本精确子串匹配（非 byte-level patch）；非重叠计数，与 Java `String.replace` 行为一致。
- 唯一匹配防盲改：0 匹配、多匹配（未开 replace_all）均报错且文件不变；不做 readState / 强制先读。
- `new_string` key 缺失 → 报错；显式空串 → 删除合法。`old_string` 空 / 与 new_string 相同 / 文件不存在 → 业务错误字符串。
- 编辑后内容超 5MB 上限 → 抛策略异常走 policy-deny（与 write_file 资源上限语义一致）；路径越界同。其余失败返回错误字符串（审计记 allow + 业务失败）。
- 成功返回"文件已编辑 + 替换 N 处 + 紧凑 diff"（old/new 超长按字符截断并标注）。
- 复用既有行内 diff observer 与 post-edit LSP 钩子；新建文件仍走 write_file。

**集成与引导**

- edit_file 加入危险工具集（HITL 审批、中危、审计），与 write_file 完全对齐。
- 工具描述含：唯一匹配要求、replace_all 用法、空串删除、"批内兄弟调用互不可见、同文件多处修改分轮顺序进行"（edits[] 落地后改为 pi 式 guideline）。
- write_file 描述与 agent 模式系统提示加"局部修改优先 edit_file"引导；AGENTS.md 核心工具数 11 → 12。
- A/B 开关：系统属性在 baseline 臂将 edit_file 从注册表（含 schema）整体移除。

## Testing Decisions

- 好测试只断言外部行为：磁盘终态、工具返回字符串、observer 回调序列、工具 schema——不断言锁的内部状态。
- 四条现有缝，不新增缝：
  1. `executeTools(batch)`（最高缝）——并发契约：提交链性质（第二次写的 before == 第一次写的 after，对调度顺序不敏感、确定性成立）；同文件不相交双编辑最终都落盘。
  2. `executeTool(name, argsJson)`——单调用语义全矩阵：唯一匹配成功、0/多匹配、replace_all、空 old_string、缺失/空串 new_string、文件不存在、路径越界、超大小上限。
  3. `setWriteFileObserver`——现有观察钩子复用为测试探针（锁内触发 → 回调顺序即提交顺序）。
  4. `getToolDefinitions()` + ApprovalPolicy 静态方法——schema 引导文案、必填参数、HITL/审计元数据、A/B 开关下工具消失。
- FileMutationLocks 不单独测，经 executeTools 间接覆盖。
- 先例：既有 ToolRegistry 测试风格（@TempDir + setProjectPath + executeTool 断言返回串与磁盘内容）。
- 并发测试需多跑几次确认非侥幸通过（无锁时提交链断言为 flaky-fail）。

## Out of Scope

- **edits[] 事务化多处编辑（MultiEdit）**：fast-follow。前置依赖内置工具的"原始 JSON 入参"通道（现有 Map<String,String> 管道传不进数组）；语义按 pi：全部 oldText 对原始内容全量校验（0 匹配/歧义/区间重叠报错），通过后内存应用、单次写盘，全有或全无。
- CRLF / 行尾容忍匹配、智能引号归一、模糊匹配（fast-follow 候选）。
- read_file 加读锁；create_project / revert_turn 的锁覆盖（revert_turn 需要全局锁语义，另行设计）。
- 跨进程互斥（OS 文件锁）。
- readState / read-before-edit 守卫。
- 用 edit_file 新建文件。
- write_file 行为变更（仅加锁 + 描述文案，执行语义不动）。
- TUI ToolCallRenderer 对 edit_file 的专属标签/path 提取（review 次要改进，未排期）。
- plan / team-worker 模式提示词及 README、agents-reference 的联动更新（review §5 的扩展范围，待单独裁决）。
- SWE-bench trace normalize 把 edit_file 归类为 edit（eval 侧，待单独裁决）。

## Further Notes

- 设计与计划文档：`docs/superpowers/specs/2026-06-05-edit-file-tool-design.md`（2026-06-12 已合入评审决策）、`docs/superpowers/plans/2026-06-05-edit-file-tool.md`（含 Task 0 文件锁前置任务）、`docs/superpowers/specs/2026-06-10-edit-file-tool-spec-review.md`。
- 并发决策记录：`docs/adr/0001-per-file-mutation-lock.md`；术语见根目录 `CONTEXT.md`（文件互斥锁、工具批次）。
- 方案来源：pi agent 的 file-mutation-queue + applyEditsToNormalizedContent 设计，按 PaiCLI 的 Java 线程模型与 HITL/审计/快照设施做了最小化适配。
- 固有限制（接受并已引导）：工具批次内兄弟调用互不可见；锁保证串行落盘不丢更新，相交编辑由唯一匹配机制明确报错、模型重试。
- 验收的北极星指标：SWE-bench-java A/B 的 resolve rate Δ + edit_file/write_file 使用占比 + max_output_tokens 截断次数变化。
