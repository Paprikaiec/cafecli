# 输出只有过程与答案两类，工具块自己带着结果

ADR 0011 定了类型化块列表，并列出四种可折叠块：工具调用组 / 代码块 / 思考引用 / Diff。ADR 0012 把折叠
交给全屏[[转写浏览层]]，默认流退化为追加式回卷。两年之后回头看，那张四类表在**产地**上是混的，而它掩盖
了一个更大的空缺：

- `ReasoningBlock` 来自模型的 `reasoning_content`，不是工具。
- `ToolCallBlock` 来自 `appendToolCalls`（`Agent.java:330`），是模型**请求**的调用——只有参数，没有结果。
- `CodeBlock` 来自 `appendCodeBlock`（`Agent.java:1409`），是 `routeAnswerLine` 从**答案流**里切出来的
  围栏代码。它就是答案。
- `DiffBlock` 来自 `ToolRegistry.setWriteFileObserver`（`Main.java:327`）。

四类里只有一类是工具调用，一类根本是答案的一部分。而**工具的结果一个字节都不上屏**：
`executeToolCalls` 把结果放进对话历史与记忆就结束了，唯一的例外是 `Agent.emitToolResultSummary`
（`Agent.java:971`）给 `web_search` / `web_fetch` 打的一行暗色 `→ …`，走 `stream()`，是普通文本、不是块。
`read_file` / `grep_code` / `execute_command` / `glob_files` / `list_dir` 跑完之后屏幕上什么都没有。那个
per-tool formatter 的 `switch` 历经 21 期长到了**两条**分支——它不是一个会长大的东西，它是「大多数工具
什么都不显示」的另一种写法。

本 ADR 取代 ADR 0011 的块类型表，并修订 ADR 0012 的一条后果。它不重开 ADR 0010 的活区合成器边界，也不
重开 ADR 0012「原地折叠取消」的决策——工具块**从不在冻结之后改变**，这一点是本 ADR 的地基而不是例外。

## D1. 输出分两类：过程与答案

**过程** = 思考 + [[工具块]]。**答案** = 正文 prose 与它自己的围栏代码。`CodeBlock` 归**答案**，不是工具
产物——把它折进 3 行会让模型写的示例代码无法阅读，而它的生产者字面上就是答案流。

[[提交块]]与 `FrozenTextItem`（开屏 Banner、斜杠命令输出、后台完成通知、`⏹️ 已请求取消`）**两类都不属于**，
它们是**会话记录**。两分类只覆盖模型的产出。

### Why

把思考塞进「工具调用」是把一个词改名成谎话。命名为**过程**之后两分类才成立：用户读的是「它做了什么」
（过程）与「它告诉我什么」（答案），这正是[[转写浏览层]]默认展开工具 / 代码 / diff / 答案而把思考留成
折叠头的那条分界（ADR 0013）。

## D2. 工具块是一个块，开在活区、完成后提交一次

一批工具调用产生**一个**[[工具块]]。`appendToolCalls(calls)` 在[[活区]]里开出它，只有调用头；新的
`appendToolResults(results)` 填进结果行并使它**完成**；完成之后它才是 settled，才被提交进[[冻结回卷]]。
**它从不在冻结之后改变。**

`freezeSettled` **一行不改**：那条规则提交的是**已完成**的可折叠块，而一个还没填结果的工具块本来就不是
完成态。它在活区里等着，与 spinner / 审批卡同属瞬时内容——这正是[[活区尾]]存在的理由。

### Why

三种形态被比较过：

- **两个块**（头立即提交、结果另成一块）：后台 `TaskDone` 通知或一条[[转向消息]]的[[提交块]]可以在两者
  之间提交，于是这一对在[[冻结回卷]]与[[转写浏览层]]里被劈开。否决。
- **等结果到齐再整块吐出**（执行期屏上只有 spinner）：结构最干净，但用户看不到**哪个**工具在跑。
- **活区里开、完成后提交**：取此。

`toolRegistry.executeTools(invocations)` 是**阻塞**的，整批一起返回（`Agent.java:332`），所以「按调用逐个
完成」在今天任何一层都不存在——这不是本 ADR 的约束，是现状，且它让「一批一个块」成为自然粒度。

代价两条，都接受：执行**期间**提交的东西（后台通知、转向消息的提交块）会落在这个工具块**前面**，尽管调用
先发生。那本来就是规则（ADR 0010 修订 A8b：帧内的写出顺序就是登记顺序），且对转向消息而言正是 ADR 0016
D5 要的位置。第二条见 D6。

## D3. 上限的单位按角色分：头用源单位，结果用视觉行

- **调用头**：≤ 2 [[逻辑行]] 或 160 字符，取先到者。（今天是 1 行 / 80 字符，`ToolCallBlock.java:118`。）
- **结果预览**：≤ 3 **视觉行**（本帧宽度下折行之后的物理行）。
- **`DiffBlock` / `CodeBlock`**：不变，前 100 **源行**。

### Why

看上去这引入了两种单位，实则没有：`CodeBlock` / `DiffBlock` 用 `BlockSource.LineLayout.CLIP`——过宽的源行
被裁掉尾巴带 `…`，从不折行——所以对它们而言 **1 源行 == 1 视觉行**，两种单位重合。只有会**折行**的内容
（工具结果的裸文本）才分得开，而那里视觉行是对的单位，因为你花的就是屏幕预算：一条 400 字符的 `grep` 输出
按源行算是 1 行，按视觉行算在 80 列下是 5 行。

反过来，调用头用源单位是因为它是**身份**：`Shell(mvn -q test)` 应该在任何宽度下都是同一个东西。

接受的后果：结果的 `… +N 行` 随宽度变（窄化时 3 行装得下的源行更少，N 变大）。这**不是** ADR 0010 修订
A11 那个陷阱——A11 说的是流式答案「已提交前缀 / 活区尾」之间的**边界**必须在源坐标里，因为那条边界要跨
投影存活；一个已保留条目的截断点没有这个要求，[[destructive resize reset]] 本来就按新宽度从源重画它。

## D4. 对写文件的工具，`DiffBlock` 就是结果视图

`write_file` 与 `edit_file` 的结果**是** diff，不额外再给 3 行文本预览。两者都经 `persistFileContent` →
`writeFileObserver`（`ToolRegistry.java:512` 与 `:795`），新建文件时 `before == null`。

`create_project` **不发 diff**：它用裸 `Files.writeString` 写脚手架（`ToolRegistry.java:1149-1164`），
不走 `persistFileContent`。这不是要补的洞——它不是文件**变更**工具，是脚手架生成器，四份 `pom.xml`
样板的 diff 是噪音。它按普通工具拿 3 行文本结果。

于是「100 行」不是钉在 3 行规则上的例外，而是同一条规则的另一半：**结果的行数上限按结果类型定**——
文本 3 视觉行，diff 100 源行。

## D5. 只有 `execute_command` 有运行中的尾

运行中的命令显示**最后 5 个非空行**；并发命令**共享** 5 行总预算（一条命令占 5 行，四条各占 1 行）。命令
结束时这片尾巴被 D3 的 3 行头**替换**掉。

**这两个数是拍的，不是量的。** 判据应是「[[活区尾]]还剩几行才算有用」，要在真机 80×24 上量，至今未做。
不要把它们当实测值引用——ADR 0016 的 20 / 12 已经在 AGENTS.md 里留过这道疤。

### Why

`execute_command` 是唯一一个时长无界、且用户**真的**分不清有没有在动的工具（`read_file` / `grep_code`
毫秒返回）。它需要一条新缝：`readProcessOutput`（`ToolRegistry.java:2319`）今天在专用线程上逐行读进
`StringBuilder`，但**直到 `waitFor` 返回之前什么都不发布**——逐行的 observer 要新加。

**一个跑着的工具块不是被推迟冻结的完成块。** 它长得像 ADR 0012 否决的那个东西（为了原地展开而留活），但
不是：那里被留活的块**已经完成**，这里的块**还没有结果**。前者是把不可变的东西按住不放，后者是瞬时内容
待在瞬时内容该待的地方。这句话必须写下来，因为它看上去就是被否决的那个。

## D6. 不做跨迭代合并，也不为浏览层提前提交头

**跨迭代合并**（Claude Code 的「Searched for 2 patterns, read 1 file」）不做。`ToolCallBlock` 已有的
**批内**分组（`读取 3 个文件`）保留。

**运行中的工具块不进 `ReplayLog`**，所以回合中开[[转写浏览层]]看不到它，要等结果落定。

### Why

跨迭代合并要么改写已进[[冻结回卷]]的块（一个 [[geometry epoch]] 内冻结行永不重建），要么把每个 read /
search 块的提交扣住等一个非 read/search 工具出现（被否决的延迟冻结，而且延迟无界）。批内分组已经拿到大
部分观感收益，代价为零。放弃的是：跨 6 轮读 6 个文件会出 6 个头而不是 1 个。真咬到了，便宜的修法在提示词
侧（鼓励批量），不在渲染器侧。

提前提交头则要在有序提交日志里放一个**可变**条目，正是 ADR 0016 D1 的隐患——输入线程排版一个渲染线程还在
填的条目。它可解（`AnswerSource` 那套：不可变快照经 volatile 发布），但那是把一个已经付过一次的模式再付
一次，换来的只是「主屏上已经在显示运行中尾」的那段窗口里浏览层也能看见。推迟；缓解手段存在，成本已知。

## D7. 「ctrl+o 查看全部」的禁令随它的前提退役

ADR 0012 的后果写着「折叠头不再印『ctrl+o to expand』式承诺文案」，而 `… +N 行 (ctrl+o 查看全部)` 一直
存在于 `DiffBlock` / `CodeBlock`。本 ADR 把它扩到每个溢出的工具结果，并**明写**：那条禁令的前提没有了。

### Why

禁令有一个具体理由：`toggleLastFoldable` 承诺原地展开却几乎永远兑现不了，因为用户读到折叠头时块早已冻结。
[[转写浏览层]]**兑现得了**。所以禁的是「兑现不了的承诺」，不是「提及 ctrl+o」。今天代码与自己的 ADR 无声
矛盾，本条把矛盾解掉而不是让它继续。

[[转写浏览层]]里工具结果**完整展开**，不设第二道上限——那会造出一份哪儿都看不到的内容。无界的担心在上游
已经解决：`MAX_COMMAND_OUTPUT_CHARS = 8000` 在源头截断 shell 输出，MCP 结果 ≥25k chars 落盘、热上下文只
留 digest（ADR 0009）。`OverlayDocument` 只排版视口，长结果的持有成本是每条目一个 int。

## 被否决的方案

- **把 `CodeBlock` 归进工具调用**：它的生产者是答案流，折进 3 行等于把模型写的代码毁掉。
- **强行两分类、思考归入工具调用**：改名成谎话。用「过程」命名非答案的那一半才成立。
- **per-tool 结果 formatter**（扩 `emitToolResultSummary` 的 `switch`）：21 期长出两条分支，实际语义是
  「大多数工具什么都不显示」。裸文本头统一处理。
- **头与结果两个块**：后台通知 / 转向消息可插在中间，劈开这一对。
- **等结果到齐再整块吐出**：执行期看不到哪个工具在跑。
- **结果也用源行做上限**：一条 400 字符的 `grep` 输出按源行算是 1 行，屏幕预算失控。
- **浏览层给工具结果第二道上限**：造出哪儿都看不到的内容。

## 后果

- `Renderer` 的块事件多一对：`appendToolCalls(calls)` 开、`appendToolResults(results)` 关，按
  `tool_call_id` 配对。按 ADR 0011 的约定，新方法给 default 实现（落回 `stream()`），`PlainRenderer` 与
  spawner 的 no-op renderer 零改动。
- `Agent.emitToolResultSummary` 与它的 `web_search` / `web_fetch` 摘要退役，由统一结果路径取代。它今天走
  `stream()`、是 `FrozenTextItem`，会在工具块还活着时抢先提交——留着它就是 D2 明确要避免的劈开。
- `ToolRegistry.readProcessOutput` 需要一个逐行 observer。这是本 ADR 唯一一处工具层改动。
- **屏幕真相判据**：这套设计能产生的具体缺陷是**副本**——块先在活区画、再提交进回卷，活区行没在冻结批
  写出之前擦掉就是两份，而 AGENTS.md 明确「画了再原地擦掉」与「留在屏上被滚走」**字节完全相同**。只有
  `TerminalScreenModel` 分得开。不变式：**一次跑完的工具调用在屏上恰好出现一次，且它的结果行与「直接以
  完成态提交」逐行相等**。
- `StripEntryContractTest` 会要求新块类型要么被检查、要么进 `NEVER_RETAINED`，并要求 `render()` 不改变
  条目任何字段——满足得了：工具块只在**生产者事件**上变，从不在 render 上变。
- 真机确认欠着，归 `.scratch/unified-output-taxonomy/issues/04-real-terminal-acceptance.md`。它自带一项
  **测量**任务：3 视觉行是 Claude Code 的数，而 Claude Code 没有[[状态坞]]——80×24 上一批 4 个并行调用按
  2 行头 + 3 行结果是 20 行，而活区尾在输入框 + 状态坞 + 横线之后只剩约 17 行。块是**提交**而非常驻，
  所以它滚过去而不是撑破活区，但一批就能超过一屏。
- **本 ADR 取代** ADR 0011 后果段的块类型表（工具调用组 / 代码块 / 思考引用 / Diff 四类可折叠）。
- **本 ADR 修订** ADR 0012 后果段「折叠头不再印『ctrl+o to expand』式承诺文案」，见 D7。

## 落地状态（2026-08-31）

**issue 01 已落地**（D2 / D3 / D7 后半）：`Renderer.appendToolResults` 带 default 实现（落回 `stream()`，
按**源行**截断——没有块模型的渲染器也没有帧宽度），`PlainRenderer` 与 spawner 的 no-op renderer 零改动；
`Agent.executeToolCalls` 在阻塞的 `executeTools` 返回后整批调一次；`ToolCallBlock` 开在[[活区]]、结果到齐
才提交；两道上限按角色分派；溢出文案收成一处常量 `render/BlockOverflow.line(int)`；
`Agent.emitToolResultSummary` 及 `webSearchSummary` / `webFetchSummary` /（随之失去调用方的）
`compactOneLine` / `isStepSearchResult` 已删除。

**一处实现选择，本 ADR 原文未指定**：`ToolCallBlock` 不再继承 `LiveFoldableBlock`，而是自己实现
`StripEntry`。这正是「`freezeSettled` 一行不改」得以成立的机制——那条规则写的是 `instanceof
LiveFoldableBlock`，一个运行中的工具块不是它，所以规则原样覆盖本案，不需要「跳过未完成块」的分支。
连带地它的 `freezeTopRows` 在未完成时返回空行（同 `ApprovalBlock` / `ActivityBlock`），使[[活区尾]]的
溢出冻结路径也提交不了它。

**issue 02 已落地**（D4）：`LiveRegionStrip.addDiff` 把 `appendDiff` 送来的 `DiffBlock` 交给**正在跑的**
那个工具块（`ToolCallBlock.attachDiff`），因为写 observer 是在 `executeTools` 里发的，必然落在
`appendToolCalls` 与 `appendToolResults` 之间；屏上因此是「调用头 + `Update <path>` + diff 行」一个块，
中间没有分隔空行。没有批次开着时仍成一个独立块（`RenderReproDriver` 的形态）。

**两处实现选择，本 ADR 原文未指定**：

1. **上限按结果类型分派做成了多态而不是条件**：`ToolCallBlock` 里每个结果是一个 `ResultView`（sealed，
   `TextResult` / `DiffResult`），能画多少由视图自己回答——文本 3 视觉行、diff 交给 `DiffBlock` 自带的
   100 源行。这样「100 行不是钉在 3 行规则上的例外」在代码里是**结构**而不是注释：没有任何地方问
   「这是不是 diff」。
2. **「跳过文本结果」的判据是两条而不是一条**：工具确实是走 `persistFileContent` 的那两个之一
   （`write_file` / `edit_file`），**且**这一 `path` 的 diff 真的到了。只按工具名跳会在写**失败**时把
   `"写入文件失败: …"` 一起丢掉——observer 那时根本没发过，调用头底下会空无一物。按 `path` 比对是精确
   的：`ToolRegistry` 交给 observer 的就是模型写的那个字符串。顺带把 `edit_file` 补进调用头的 `path` 键
   与中文标签（`编辑 N 个文件`），此前它落到 default 分支、把整串参数 JSON 打在头上。

**issue 03 已落地（D5）**：`ToolRegistry.setCommandOutputObserver(command, line)`（形状照抄
`setWriteFileObserver`，是本 ADR 唯一一处工具层改动）在 `readProcessOutput` 的读取循环里逐行发布，不再
等 `waitFor`；`Main` 把它接成 `Renderer.appendCommandOutput`，inline 那一档只投一个渲染事件——observer
线程不写终端。尾巴显示最后 5 个非空行、并发命令共享这 5 行，命令结束时由 D3 的 3 行结果头**替换**掉
（`ToolCallBlock.completeWith` 清空它），超时被 `destroyForcibly` 杀掉的命令走同一条路。

**三处本 ADR 原文未指定，在此记下**：(1) `appendCommandOutput` 的 default 是**空实现**而不是像两个块事件
那样落回 `stream()`——瞬时内容在没有[[活区]]的渲染器里没有位置，打出来等于把命令输出上屏两次（一次滚过，
一次在结果里）；这条 default 的选择因此不是疏忽，而是「过程 / 答案」分类学在降级渲染器上的同一句话。
(2) 运行尾按**裁行**（`BlockSource.clip`）而不是折行，所以 5 行预算是精确的；完成态结果反过来折行，两者的
单位分歧与 D3 同因。(3) 并发条目多于 5 时**先开口的先占**，其余等着——「每条至少一行」会让预算失去它存在
的意义。

**只剩 issue 04（真机验收 + 3 / 5 两个数的测量）尚无代码。** 01 / 02 / 03 的判据**全部离线**：
`ScreenTruthRegressionTest`（issue 01 的两条对照组用例、issue 02 的 `create_project`「有头有文本结果、
没有 diff」对照、issue 03 的七条含「运行尾不留在屏上」与它的两个对照组）、`ToolCallBlockTest`（三道上限
与五条运行尾用例）、`ToolRegistryTest`（逐行 observer 四条）、`StripEntryContractTest`。**三者都未在真机
确认**，而 **3 与 5 这两个数至今是拍的不是量的**——归 issue 04，别当实测值引用。

**02 与 03 是并行开发后合并的**，合并时只有一处需要判断：`LiveRegionStrip` 里 diff（D4）与运行中输出行
（D5）都是「找那个还开着的批次」，两边各写了一遍走法不同的搜索。合并成同一个 `runningTools()`——它们问的
是同一个问题，两份答案迟早会分头漂移。
