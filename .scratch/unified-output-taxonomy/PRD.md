# PRD: 输出只有过程与答案，工具块自己带着结果

Status: ready-for-agent
Date: 2026-08-30
Branch: TBD（建议 `feat/unified-output-taxonomy`）
Decision record: **ADR 0017**（本 PRD 的决策记录，取代 ADR 0011 的块类型表、修订 ADR 0012 的两条后果）；
尊重 ADR 0010（活区合成器 + 修订 A8b 写出顺序 / A11 源坐标 / A16 屏幕真相判据）、ADR 0013（浏览层折叠思考）、
ADR 0016 D1（[[条目源与视图缓存]]）

## Problem Statement

**工具跑完之后屏幕上什么都没有。** `Agent.executeToolCalls` 把结果放进对话历史与记忆就结束了；唯一例外是
`Agent.emitToolResultSummary`（`Agent.java:971`）给 `web_search` / `web_fetch` 各打一行暗色 `→ …`，走
`stream()`，是普通文本、不是块。`read_file` / `grep_code` / `execute_command` / `glob_files` / `list_dir`
的结果一个字节都不上屏。那个 per-tool formatter 的 `switch` 历经 21 期长到了**两条**分支——它不是一个会
长大的东西，它是「大多数工具什么都不显示」的另一种写法。

用户能直接撞到的三件事：

1. **看不到工具做了什么。** `Shell(mvn -q test)` 跑完，屏上只有一行折叠头，没有 exit code、没有输出。要
   知道发生了什么只能进[[转写浏览层]]，而那里也只有调用参数——**结果从来没被送进渲染器**，浏览层里也没有。
2. **长命令期间不知道有没有在动。** `execute_command` 在专用线程上逐行读进 `StringBuilder`，但直到
   `waitFor` 返回之前什么都不发布（`ToolRegistry.java:2292-2319`）。一条跑三十秒的命令期间，屏上只有 spinner。
3. **折叠头的四类表按产地是混的。** ADR 0011 列的「工具调用组 / 代码块 / 思考引用 / Diff」里，只有
   `ToolCallBlock` 是工具调用：`ReasoningBlock` 来自模型思考，`CodeBlock` 来自**答案流**
   （`Agent.routeAnswerLine` 切出的围栏代码），`DiffBlock` 来自 write observer。按这张表去「统一」，最容易
   犯的错就是把答案里的示例代码折进几行。

## Solution

**输出分[[过程]]与答案两类；[[工具块]]自己带着结果。**

从用户视角：

- 每次工具调用在转写里是**一个块**：`Shell(mvn -q test)` 这样的调用头，底下跟着结果的前几行，多出来的标
  `… +N 行 (ctrl+o 查看全部)`。写文件的工具，结果就是 diff。
- **命令跑着的时候看得见它在动**：`execute_command` 执行期间框下方滚动显示最后几行输出，命令结束后换成
  完成态的结果头部。
- **Ctrl+O 进浏览层能看到完整结果**，不只是调用参数。
- 答案里的围栏代码**不受影响**——它是答案的一部分，不是工具产物。

技术形态（ADR 0017）：`Renderer` 加一对块事件，`appendToolCalls(calls)` 在[[活区]]里开出工具块（只有头），
`appendToolResults(results)` 填进结果并使它完成；完成之后才被提交进[[冻结回卷]]，**从不在冻结之后改变**。
`freezeSettled` 一行不改——它提交的是**已完成**的可折叠块，没填结果的工具块本来就不是完成态。

## User Stories

1. As a Cafecli 用户, I want 工具跑完后在转写里看到它的结果前几行, so that 我不必进浏览层就能判断它做对了没有。
2. As a Cafecli 用户, I want 一次工具调用在转写里是**一个**块（调用与结果不分家）, so that 我不必在屏上把它们对起来。
3. As a Cafecli 用户, I want 结果多出来时看到 `… +N 行 (ctrl+o 查看全部)`, so that 我知道还有内容以及去哪看。
4. As a Cafecli 用户, I want Ctrl+O 里的工具块展开出**完整**结果, so that 出问题时我能读到全文而不是摘要。
5. As a Cafecli 用户, I want 长命令执行期间看到它的输出在滚, so that 我知道它没有卡住而不是白等三十秒。
6. As a Cafecli 用户, I want 命令结束后运行尾被完成态结果替换掉, so that 屏上不会同时留着同一条命令的两份输出。
7. As a Cafecli 用户, I want `edit_file` 的结果就是 diff 本身, so that 我不用读一句「共 N 条编辑」的摘要再去看下面的 diff。
8. As a Cafecli 用户, I want 答案里的示例代码照常完整显示, so that 「统一输出格式」不会把我要读的代码折掉。
9. As a Cafecli 用户, I want 调用头在任何终端宽度下都是同一个东西, so that 我能靠它认出「这是刚才那条命令」。
10. As a Cafecli 用户, I want 缩放窗口后工具块按新宽度重画且不留残行, so that 改窗口大小不会把转写弄脏。
11. As a Cafecli 用户, I want 回合期间提交的[[转向消息]]落在它实际发生的位置, so that 我事后能看出这条纠正插在哪一步。
12. As a Cafecli 用户, I want `web_search` 的结果与别的工具长得一样, so that 我不用记住哪些工具有摘要、哪些没有。
13. As a Cafecli 维护者, I want 新工具无需注册任何格式化代码就能显示结果, so that 「大多数工具什么都不显示」不再是默认状态。

## Implementation Decisions

- **`Renderer` 加一对块事件**，不是改既有签名：`appendToolCalls(calls)` 开、`appendToolResults(results)` 关，
  按 `tool_call_id` 配对。按 ADR 0011 的约定新方法给 default 实现（落回 `stream()`），`PlainRenderer` 与
  spawner 的 no-op renderer 零改动。这是本 PRD 唯一的接口面扩张。
- **一批调用 = 一个块。** 粒度不是"每次调用一个块"：`executeTools` 是阻塞的、整批返回，逐个完成的结果在
  今天任何一层都不存在。
- **生命周期靠既有规则，不加分支。** 块在[[活区]]里开出（只有头，不是完成态），结果到齐才完成、才被
  `freezeSettled` 提交。`freezeSettled` **一行不改**——它提交的就是已完成的可折叠块。
- **结果内容是裸文本头部**，没有 per-tool formatter。`Agent.emitToolResultSummary` 及其两个 helper 退役。
- **上限按角色分派**（头用源单位、结果用视觉行、diff 用源行），实现读起来应是"按结果类型分派"而不是
  "diff 是个特例"。
- **`ToolRegistry` 加一个逐行 observer**（`readProcessOutput`），形状与既有的 `writeFileObserver` 相同。
  这是本 PRD 唯一一处工具层改动，且只服务 `execute_command`。
- **`DiffBlock` 是 `write_file` / `edit_file` 的结果视图**，这两个工具的文本结果不再另行显示。
  `create_project` 不接 observer，保持现状。
- **`CodeBlock` 一行不改**——它归答案。

## Testing Decisions

**好的测试只断言外部可观察的行为。** 这个仓库有一条更强的补充（ADR 0010 修订 A16）：涉及活区与冻结回卷
时，**字节断言不够**。「画了再原地擦掉」与「留在屏上被滚走」字节完全相同，只有屏幕模型分得开。本 PRD 引入
的正是这种缺陷形态（块先在活区画、再提交进回卷），所以主判据必须读屏。

**三个缝，全部已存在，不新增：**

1. **渲染器事件 → 屏幕模型**（`ScreenTruthRegressionTest` / `TerminalScreenModel`）。直接驱动
   `appendToolCalls` / `appendToolResults`，断言屏上的结果。这是**最高**的、也是唯一能看见「副本」缺陷的缝，
   01 / 02 / 03 的主判据都挂在这里。
2. **`ToolRegistry` observer**（`ToolRegistryTest`）。03 的逐行 hook 用它。**prior art 是精确的**：
   `setWriteFileObserver` 的既有用例是同一形状。
3. **`StripEntryContractTest`**（反射，自动）。新块类型要么被检查、要么进 `NEVER_RETAINED`；且
   `render()` 不得改变条目任何字段——满足得了，工具块只在**生产者事件**上变。

**明确不设的缝**：不在 `Agent` 上用 recording `Renderer` 断言「Agent 调了 `appendToolResults`」。那测的是
接线而不是行为，而缝 1 已经覆盖了行为。缝越少越好。

**每个 issue 都要带对照组**，这是本仓库反复证明有效的形态：02 的「`create_project` 没有 diff」、03 的
「`read_file` 执行期间活区尾不新增行」、01 的「不带转向消息时逐行相等」。没有对照组的屏幕真相断言容易
在一个从未存在过的屏幕上全绿（ADR 0010 修订 A19 的教训）。

## Scope

**D1（分类学）没有对应 issue，因为代码里它已经成立**：`CodeBlock` 的生产者字面上就是答案流，没有任何地方
把它当工具产物；浏览层的折叠默认（工具 / 代码 / diff / 答案展开，思考折叠）也不变。D1 落在 ADR 0017、
`CONTEXT.md` 的[[过程]]与[[工具块]]两条，以及**不要顺手去动 `CodeBlock`** 这条纪律上。

## Issues

| # | 标题 | Blocked by | Status |
|---|------|-----------|--------|
| 01 | 工具结果上屏：事件对、活区生命周期、上限与单位 | — | ready-for-agent |
| 02 | `DiffBlock` 就是写文件工具的结果视图 | 01 | done |
| 03 | `execute_command` 运行中的输出尾 | 01 | ready-for-agent |
| 04 | 真机人工验收 | 01, 02, 03 | ready-for-human |

> **上限与单位曾是独立的 02，已并入 01。** 判据是 tracer bullet 那条「完成后自身可验证」：01 单独落地会让
> 结果**无上限**渲染，一次 `read_file` 读 2000 行文件就把 2000 行灌进活区。上限不是结果通路的精修，它是
> 让这条通路可用的一部分。

## 不做（ADR 0017 D6）

- **跨迭代合并** read / search 操作（Claude Code 的「Searched for 2 patterns, read 1 file」）：要么改写已进
  [[冻结回卷]]的块（一个 [[geometry epoch]] 内冻结行永不重建），要么把每个 read/search 块的提交无界地扣住
  （被否决的延迟冻结）。批内分组已有，收益大部分已拿到。
- **为浏览层提前提交调用头**：那是往有序提交日志里放一个可变条目，正是 ADR 0016 D1 的隐患。运行中的工具块
  在浏览层里看不到，是明码标价的代价。

## 已知未量

- **3 视觉行是 Claude Code 的数，不是量出来的。** Claude Code 没有[[状态坞]]，默认终端也更宽。80×24 上活区尾
  在[[输入框]] + 状态坞 + 横线之后只剩约 17 行，而一批 4 个并行调用按 2 行头 + 3 行结果是 20 行。工具块是
  **提交**而非常驻，所以它是滚过去而不是撑破活区——但一批就能超过一屏。归 issue 04 观察。
- **5 行运行中尾同样是拍的。** 判据应是「活区尾还剩几行才算有用」，与 ADR 0016 那两个 20 / 12 是同一笔未做的
  测量。**不要把它们写得像实测值**——这个仓库为混淆这两者付过代价。
