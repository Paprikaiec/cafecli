# PRD: 生命周期 Hook 内部 seam + 收尾自检守卫（compile-gate / safe-edit / self-check）

Status: superseded by `.scratch/configurable-lifecycle-hooks/PRD.md` (ADR 0012; #04 safe-edit already shipped as PathGuard rule, #02/#03 deferred by Phase 0 evidence)
Date: 2026-06-26
Branch: feat/lifecycle-hook-seam（建议）
Decision record: ADR 0008（生命周期 Hook 内部 seam 先行）
失败归因来源: `/tmp/handoff-paicli-failure-attribution.md`（SWE-bench-Live no-edit baseline）

## Problem Statement

SWE-bench-Live 失败归因显示，PaiCLI 与参照 agent（Claude）在这组实例上模型推理基本同档——可归因于 PaiCLI 自身的失败几乎全在脚手架，而非模型。三类工程可修的复发失败形状：

1. **补丁破坏编译 → 0/0**：all-or-nothing 计分下，一个编不过的补丁拿 0 分，抹掉全部部分进度（floci-88 / 299 / 352 / grpc-12735）。
2. **从不自检**：agent 收尾前从不编译、不跑测试，把"残缺/编不过的修复"当成完成（floci-164 / 248 / jackson 全部 0 次测试运行）。
3. **改到隐藏测试文件**：编辑了被 gold test_patch 覆盖的既有测试文件，打补丁冲突、编不过 → 0/0（floci-248）。

根因层面，这些都是同一件事的缺失：**ReAct 主循环在"收尾"这个生命周期点没有任何把关**。Agent 在 `Agent.java` 自然收尾路径（LLM 不再调用工具即返回）上直接结束，既不校验产物能否编译，也没有"还没做完、继续修"的反馈闭环。同时写工具对 `src/test/**` 没有任何约束。

## Solution

按 ADR 0008，对标 Claude Code 的生命周期 hook 模型，但**只先落内部 seam**：

1. **生命周期 Hook seam**：统一的 `HookDispatcher` + 事件枚举 + `HookPayload`/`HookDecision` 契约（对齐 Claude Code 公开 hook schema）。本轮只接 `Stop`（收尾）事件，`PostToolUse`/`PreToolUse` 仅预留。
2. **compile-gate（内建 `Stop` hook）**：收尾时，若本回合改过文件，跑评测环境给定的构建命令；编不过则给出 `block` + 报错反馈、注入下一轮、续跑，让 agent 自纠正。受最大重阻次数与硬轮数兜底。直接打失败形状 #1 / #2。
3. **safe-edit（[[策略守卫]] 规则）**：在 PathGuard 增一条规则，拒绝对 `src/test/**` 的写入。属权限层、不是 hook。直接打失败形状 #3。
4. **self-check prompt-nudge**：系统提示加一句"收尾前先写最小复现并跑一下"，软性引导自检（失败形状 #2 的廉价一半）。
5. **Phase 0（零代码前置）**：先用两个开关——`edit_file` 开启 + `-Dpaicli.react.hard.max.iterations=80`——对当前 band 做一次免费再基线，再决定上面哪些守卫真正需要、做多深。

全部新行为**默认关闭、由 `run`/eval 开启**，交互产品不受影响。权限链（Guard/HITL）与 hook 分属两层、互不重写（ADR 0008，已定不重议）。

## User Stories

1. As a SWE-bench 评测工程师, I want 先用零代码开关（`edit_file` 开启 + 硬轮数 80）对当前 band 做一次免费再基线, so that 在写任何 PaiCLI 代码前先看清哪些失败形状被 edit_file/更高轮数自然吃掉、避免过早工程化。
2. As a SWE-bench 评测工程师, I want 硬轮数上限可由 `-D` 覆盖, so that floci-164 这类"差几轮收尾"的实例不再被 50 轮硬墙截断。
3. As an LLM agent, I want 我自以为做完、准备收尾时系统先帮我编译一次, so that 我不会把一个根本编不过的补丁当成完成（编不过=0 分）。
4. As an LLM agent, I want 编译失败时把报错注入下一轮并让我继续修, so that 我能在同一回合预算内把编译错误改掉而不是直接交差。
5. As an LLM agent, I want 只有当本回合确实改过文件时才触发收尾编译, so that 纯问答/只读回合不会被无谓地卡住收尾。
6. As an LLM agent, I want 收尾编译用评测环境给定的构建命令, so that 不同仓库（maven/gradle/自定义）都能用正确方式校验、而不是靠猜测自动探测。
7. As a SWE-bench 评测工程师, I want compile-gate 反复失败时有最大重阻次数、并以硬轮数兜底, so that 一个修不好的错误不会耗光整轮预算、也不会死循环。
8. As an LLM agent, I want 禁止我编辑 `src/test/**` 下的既有测试文件, so that 我不会改到被 gold test_patch 覆盖的隐藏测试、导致打补丁冲突编不过（floci-248）。
9. As an LLM agent, I want 系统提示提醒我"收尾前先写最小复现并跑一下", so that 我养成自检习惯、更早发现修不完整（即便手里有 `execute_command` 也常忘了用）。
10. As a PaiCLI 用户（交互）, I want 这些收尾守卫默认关闭、只在 `run`/eval 开启, so that 我的交互会话不会每次收尾都被强制跑构建、也不会被禁止编辑自己的测试。
11. As a PaiCLI 产品, I want compile-gate / safe-edit / self-check 由统一的模式开关控制, so that 同一份代码既服务 SWE-bench 又不打扰交互产品。
12. As a 后续贡献者, I want hook 的事件分类与 `HookPayload`/`HookDecision` 契约对齐 Claude Code 公开 schema, so that 第23期接外部 shell hook 时纯增量、不改主循环。
13. As a 后续贡献者, I want compile-gate 只是统一 `HookDispatcher` 上的第一个内建租户, so that 以后加新内建守卫（如确定性 test-gate）不必再动 Agent 收尾路径。
14. As a 后续贡献者, I want safe-edit 落在 PathGuard（权限层）而不是 hook, so that [[策略守卫]]（拒绝）与 [[生命周期 Hook]]（反馈/续跑）的边界清晰（ADR 0008 / CONTEXT.md）。
15. As a 安全审计者, I want safe-edit 的拒绝与既有路径越界拒绝走同一套策略层与审计日志, so that 写边界可解释、无旁路。
16. As an LLM agent, I want compile-gate 的反馈是结构化的 `HookDecision`（block + reason）, so that 注入给我的内容明确是"还没做完，这是构建报错"而非普通工具事实结果。
17. As a SWE-bench 评测工程师, I want 从 trace 看到 compile-gate 触发/重阻了几次、safe-edit 拒绝了几次, so that 我能解释 resolve rate 为什么变化。
18. As a PaiCLI 用户, I want compile-gate 与既有 per-edit LSP 诊断注入互补而不打架, so that 收尾硬门与过程性建议各司其职、不重复刷屏。
19. As an LLM agent, I want 当硬轮数/预算先到时 compile-gate 不再阻拦收尾, so that 兜底退出始终能让任务结束（gate 不凌驾于硬上限之上）。
20. As an LLM agent, I want 多个 `Stop` hook 注册时按确定顺序执行、第一个 block 短路, so that 行为可预测、可测。
21. As a PaiCLI 用户, I want `HookDispatcher` 在未注册任何 hook 时是零开销 no-op, so that 默认（交互）路径不为一个未启用的特性付出运行时代价。
22. As a PaiCLI 用户, I want self-check 提示只在 `run`/eval 注入系统提示, so that 交互模式的系统提示不被额外文案撑大。
23. As a SWE-bench 评测工程师, I want compile-gate 的北极星是"在再基线后的 band 上把 build-break 类 0/0 救回、并让 164 翻盘", so that 改动效果可归因、可验证。
24. As a 后续贡献者, I want 决策记录在 ADR 0008、术语（生命周期 Hook / 策略守卫）收进 CONTEXT.md, so that 我不会把"permissions 并入 hooks"或"现在就做完整 shell-hook 子系统"重新发明回来。

## Implementation Decisions

**架构边界（ADR 0008，已定不重议）**

- 只落**内部** seam：`HookDispatcher` + 事件枚举 + `HookPayload`/`HookDecision`。可配置外部 shell-hook 子系统、配置 schema、shell 执行器、其安全模型——全部延后到 ROADMAP 第23期。
- 权限层（PathGuard/CommandGuard/HITL）与 hook **保持独立、互不重写**。拒绝是 [[策略守卫]]，反馈/续跑是 [[生命周期 Hook]]。
- 本轮**只接 `Stop` 事件**（`Agent.java` 自然收尾路径）；`PostToolUse`/`PreToolUse` 仅在枚举与分发里预留，不接线。

**生命周期 Hook seam**

- `HookDispatcher` 持每事件的有序 hook 列表，`fire(event, payload)` 按序执行、第一个 `block`/`deny` 短路，聚合反馈，返回 `HookDecision`。未注册 hook 时为 no-op（零开销）。
- `HookDecision` 字段是 Claude Code hook 输出 schema 的**超集**（`continue` / `decision: block|allow|deny|none` / `reason` / `feedback`），使第23期的 `ShellHook` 能把 shell-hook JSON 映射到同一对象。
- `Hook` 接口含 `handle(payload)` + `matches(payload)` + `provenance`（`builtin` / 预留 `shell`）。
- payload 可 JSON 序列化（为第23期 stdin 协议铺路）；本轮无需真序列化。

**compile-gate（内建 `Stop` hook）**

- 触发条件：本回合至少有一次写工具（`write_file`/`edit_file`）成功落盘。无改动则 gate no-op、正常收尾（覆盖纯问答/只读回合）。"本回合是否改过文件"由主循环跟踪工具执行得出（不强依赖快照 diff）。
- 校验命令：由评测/`run` 路径经 `-D`（如 `paicli.finish.verify.cmd`）注入，编译范围（如 `mvn -q test-compile`）。**不做自动探测**（交互接入时再议）。命令缺失则 gate 不启用。
- 失败：返回 `block` + 把构建报错作为 `HookDecision.feedback` 注入下一轮（以内部 user message 形式，类同既有 `[LSP 诊断注入]`），主循环 `continue` 续跑。
- 兜底：最大重阻次数（`-D` 可配，默认小值）封顶；每次 block 消耗一轮，硬轮数上限始终是终极兜底——**预算/硬轮数先到时 gate 让路**，不凌驾于退出条件之上。
- 与 LSP 诊断注入互补：LSP 是 per-edit 过程性建议（advisory，不阻拦收尾）；compile-gate 是 finish-time 硬门（block）。compile-gate 跑真实构建命令而非复用 LSP（SWE-bench 镜像可能无 LSP server）。

**safe-edit（PathGuard 规则）**

- 在 PathGuard 增一条 deny 规则：写工具目标落在 `src/test/**`（及等价测试目录约定）时按 policy-deny 拒绝，与既有路径越界拒绝同一通道、同一审计。
- 仅在 `run`/eval 模式启用（交互下用户合法编辑自己的测试）。
- 锁 key / 路径解析复用 PathGuard 既有安全路径解析，不另造规范化层。

**self-check prompt-nudge**

- 在系统提示加一句引导（收尾前写最小复现并跑一下），仅 `run`/eval 注入，交互不注入。
- 这是软引导，不是 hook、不进 `HookDispatcher`；确定性 test-gate 延后（见 Out of Scope）。

**模式门控**

- compile-gate / safe-edit / self-check 各受开关控制，默认关闭、由 `run`/eval 打开，沿用 `AgentBudget` 的 `-D`/系统属性风格（`paicli.*`）。
- `run` 子命令负责在 headless/eval 路径开启这些守卫；交互主路径不触发。

**Phase 0（零代码前置，先行）**

- `edit_file` 默认即开启（`ToolRegistry` 中 `!editFileDisabled()`）；再基线只需构建含 `edit_file` 的 jar 且不设禁用 flag。
- `-Dpaicli.react.hard.max.iterations=80`。
- 在当前 band 重跑，作为决定 compile-gate / test-gate 必要性的依据。

## Testing Decisions

- 好测试只断言**外部行为**：注入到下一轮请求的消息内容、循环是否多跑一轮、工具返回串、policy-deny、系统提示文案、`HookDecision` 取值——不断言 dispatcher/hook 的内部字段或私有状态。
- 五条缝（四 existing + 一 new class 的最高点单元缝），不为已有行为另造缝：
  1. **`HookDispatcher` 公共 API（新单元缝）**——注册多个 hook → `fire(event,payload)` → 断言执行顺序、第一个 `block` 短路、反馈聚合、未注册时 no-op。先例：`AgentBudgetTest`（对循环支撑类的纯单元）。
  2. **`Agent.run()` + 脚本化 `LlmClient`（现有缝）**——脚本：改文件 → 收尾（无 tool call）→ compile-gate 失败注入 → 再收尾。断言循环多跑一轮、且注入的 user message 含构建报错；改对/无改动/gate 关时正常收尾。先例：**`AgentLspDiagnosticsTest`**（`RecordingClient` 重放 `ChatResponse`、`@TempDir`、断言第 N 轮请求历史）。
  3. **compile-gate `Hook.handle()`（新单元缝，trivial）**——把 `-Dpaicli.finish.verify.cmd` 指向 `true`/`false`/临时脚本（不跑真 mvn），断言 pass vs `block`+feedback、最大重阻次数被尊重、无改动时 no-op。
  4. **`ToolRegistry.executeTool` / `PathGuard`（现有缝）**——`run` 模式下对 `src/test/**` 的 `edit_file`/`write_file` → policy-deny（与路径越界同语义）；模式关或目录外时放行。先例：`ToolRegistryTest` 路径越界 + `PathGuardTest`。
  5. **`PromptAssembler`（现有缝）**——self-check 引导在 `run`/eval 开关下出现、关时不出现。先例：`PromptAssemblerTest` / `SpawnPromptDisciplineTest`。
- 模式门控经 `System.setProperty`（类 `AgentBudget` 的 `paicli.react.*`）在缝 2/4/5 内断言开/关两态。
- 确定性注意：缝 2 的 compile-gate 续跑断言要在脚本里给足响应（block 后必须有下一条收尾响应），避免 `RecordingClient` 缺响应抛错；compile 命令一律用可控 `true`/`false`/脚本，不依赖真实构建（慢且环境相关）。
- **Phase 0** 无 in-repo 测试——它是评测/harness 操作，验收看再基线 resolve rate。

## Out of Scope

- **B：用户可配置 shell-hook 子系统**——配置 schema、shell 执行器（spawn + JSON stdin + 解析 exit-code/JSON + 超时）、全部 9 类事件接线、任意 shell 的安全模型（与 CommandGuard/HITL 协调）。已记为 ROADMAP 第23期 / ADR 0008。
- **`PostToolUse` / `PreToolUse` 接线**——本轮仅预留枚举与分发，不接线（safe-edit 走 PathGuard，不需要 `PreToolUse`）。
- **把现有 Guard 链重写成 hook**（permissions 并入 hooks）——ADR 0008 明确否决。
- **确定性 test-gate（收尾跑测试的硬门）**——延后；本轮 #3a 只做软 prompt-nudge。是否需要、跑哪些测试，待 Phase 0 后再议（SWE-bench 隐藏测试模型下"跑哪些测试"无干净确定性答案、且有污染 graded 文件风险）。
- **自适应/优雅预算（near-cap 收敛信号）**——本轮只用 `-D` 抬高硬轮数；自适应延后。
- **校验命令自动探测**（pom.xml→mvn / build.gradle→gradle）——交互接入时再做；本轮 `run`/eval 经 `-D` 显式给定。
- **交互产品接入这些守卫**——本轮交互默认关闭；软模式接入是后续产品决策。
- **`run` 之外的运行入口**（serve --http 等）启用守卫——本轮只 `run`/eval。

## Further Notes

- 决策记录：`docs/adr/0008-lifecycle-hooks-internal-seam-first.md`；术语见根目录 `CONTEXT.md`（生命周期 Hook、策略守卫）；远期方向见 `ROADMAP.md` 第23期。
- 失败归因与实例证据：`/tmp/handoff-paicli-failure-attribution.md`（含 ledger：floci-164 步数、248 定位+隐藏测试、88/299/352/grpc build-break）。
- 既有邻居机制：Phase 17 的 per-edit LSP 诊断注入（`[LSP 诊断注入]`，见 `AgentLspDiagnosticsTest`）。compile-gate 不取代它——advisory vs finish-time block 互补。
- 关键代码锚点（实现时核对，勿当契约）：`Agent.java` 自然收尾路径（`response.hasToolCalls()` 为 false 的分支）；`AgentBudget`（硬轮数/`-D` 风格）；`ToolRegistry` 的 `editFileDisabled()`（Phase 0 开关语义参照）；`policy/PathGuard`。
- 验收北极星：Phase 0 再基线后，build-break 类 0/0 的救回数、floci-164 是否翻盘、compile-gate 触发/重阻次数与 safe-edit 拒绝次数（可从 trace 归因）。
- 前向兼容验收：`HookDecision`/事件契约是否足以让第23期 `ShellHook` 纯增量接入、主循环不改。
