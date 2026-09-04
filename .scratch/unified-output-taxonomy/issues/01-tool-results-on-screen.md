# 01 — 工具结果上屏：事件对、活区生命周期、上限与单位

Status: ready-for-agent
Type: 常规（离线判据齐全；视觉验收归 04）
Blocked by: None (can start immediately)

## Parent

`.scratch/unified-output-taxonomy/PRD.md` · ADR 0017 D2、D3、D7 后半

## What to build

**工具跑完之后，转写里出现一个块：调用头 + 结果的前几行，多出来的标 `… +N 行 (ctrl+o 查看全部)`。**
Ctrl+O 进浏览层看到的是完整结果。今天这些工具跑完屏上什么都没有。

### 1. 事件对与生命周期（D2）

`Renderer` 加一个块事件，与既有的 `appendToolCalls(List<ToolCall>)` 配成一对：

```java
default void appendToolResults(List<ToolExecutionResult> results) { /* 落回 stream() */ }
```

按 ADR 0011 的约定给 default 实现，`PlainRenderer` 与 `ForegroundAgentSpawner` 的 no-op renderer
**零改动**。两个事件按 `tool_call_id` 配对。

`Agent` 接线：`executeToolCalls` 拿到结果之后（`executeTools` 是**阻塞**的，整批一起返回）调
`appendToolResults(results)`。

**一批工具调用 = 一个块**：`appendToolCalls` 把它加进[[活区]]，此时只有头、**不是完成态**；
`appendToolResults` 填进结果并标记完成，之后它才被 `freezeSettled` 提交进[[冻结回卷]]，
**从不在冻结之后改变**。

> **`freezeSettled` 一行不改。** 它提交的是**已完成**的可折叠块，而没填结果的工具块本来就不是完成态——
> 它在活区里等着，与 spinner / 审批卡同属瞬时内容。**不要**给它加一个「跳过未完成块」的分支：那会把一条
> 本来自然成立的规则写成一个特例。

### 2. 上限与单位（D3）

| 内容 | 上限 | 单位 |
|------|------|------|
| 调用头 | ≤ 2 行或 160 字符，取先到者 | **[[逻辑行]]**（源） |
| 结果预览 | ≤ 3 行 | **视觉行**（本帧宽度折行之后） |
| `DiffBlock` / `CodeBlock` | 100 行（不变） | 源行 |

- 今天调用头是 1 行 / 80 字符（`ToolCallBlock` 的 `extractKeyParam` 截断）。放宽到 2 逻辑行 / 160 字符。
- 调用头保持 `ToolName(裸参数)` 形态：`collapsedLabel` 今天已经在 `count == 1` 时输出
  `Shell(...)` / `ReadFile(...)` / `WebSearch("...")`，多次同名调用退化为中文分组标签（`读取 3 个文件`）。
  这条**不用重写**，只放宽字符预算并允许第二行。
- 溢出文案与 `DiffBlock` / `CodeBlock` **一字不差**，且只应存在一处常量。

**为什么是两种单位（别"统一"掉）**：`CodeBlock` / `DiffBlock` 用 `BlockSource.LineLayout.CLIP`——过宽的源行
裁尾带 `…`，从不折行——所以对它们而言 **1 源行 == 1 视觉行**，两种单位重合。只有会**折行**的内容（工具结果
的裸文本）才分得开，而那里视觉行是对的单位：一条 400 字符的 `grep` 输出按源行算是 1 行，按视觉行算在 80 列
下是 5 行。调用头用源单位是因为它是**身份**，应当跨宽度稳定。

### 3. 结果内容与展开

- 结果文本取 `ToolExecutionResult.result()` 的**裸头部**，**不做 per-tool 格式化**。
- 浏览层里工具块展开出**完整**结果，不设第二道上限（D7）——那会造出哪儿都看不到的内容。无界性在上游已解决
  （shell 输出源头截断、MCP ≥25k 落盘只留 digest）。

### 4. 退役 `Agent.emitToolResultSummary`

连同 `webSearchSummary` / `webFetchSummary` 一并删除。它今天走 `stream()`、落成 `FrozenTextItem`，会在工具块
**还活着的时候**抢先提交——留着它就是 D2 明确要避免的「这一对被劈开」。`web_search` / `web_fetch` 的结果改由
统一路径显示。

## 不要做

- **不要把头与结果做成两个块**。后台 `TaskDone` 通知或一条[[转向消息]]的[[提交块]]可以在两者之间提交，
  于是这一对在冻结回卷与浏览层里被劈开（ADR 0017 D2 已否决）。
- **不要给运行中的块提前登记 `ReplayLog`**（D6）：那是往有序提交日志里放一个可变条目，正是 ADR 0016 D1 的
  隐患。运行中的工具块在浏览层里看不到，这是接受的代价。
- **不要碰 `CodeBlock`**。它来自答案流，归答案（D1）。「统一输出格式」这句话最容易导致的错误就是把它折掉。

## 接受的后果（写进注释，别当缺陷改掉）

- 执行**期间**提交的东西——后台通知、转向消息的提交块——会落在这个工具块**前面**，尽管调用先发生。那本来
  就是规则（ADR 0010 修订 A8b：帧内的写出顺序就是登记顺序），对转向消息而言正是 ADR 0016 D5 要的位置。
- 结果的 `… +N 行` **随宽度变**（窄化时 3 行装得下的源行更少，N 变大）。这**不是** ADR 0010 修订 A11 那个
  陷阱——A11 管的是流式答案「已提交前缀 / 活区尾」之间的边界，那条边界要跨投影存活；一个已保留条目的截断点
  没有这个要求，[[destructive resize reset]] 本来就按新宽度从源重画它。

## Acceptance criteria

- [x] `appendToolResults` 在 `Renderer` 上有 default 实现；`PlainRenderer` 与 spawner no-op renderer 未被修改
      （可 grep 复核）—— `git diff --name-only` 里两者都不出现；spawner 的 `stream()` 是丢弃流，所以继承来的
      default 无副作用
- [x] 一次「调用 → 执行 → 结果」之后，转写里是**一个**块，含调用头与结果行 ——
      `ScreenTruthRegressionTest.aCompletedToolCallIsOnScreenOnceAndMatchesTheSameBlockCommittedAlreadyComplete`
      里的 `blankRowsBetween(header, result) == 0`
- [x] **屏幕真相判据（本 issue 的主判据）** ——
      `ScreenTruthRegressionTest.aCompletedToolCallIsOnScreenOnceAndMatchesTheSameBlockCommittedAlreadyComplete`
      （对照组 = 同一串事件、中间不插帧，块从未进过活区）
- [x] 工具执行期间提交的[[提交块]]（转向消息）落在工具块**之前** ——
      `ScreenTruthRegressionTest.aSteeringEchoSubmittedWhileAToolRunsLandsBeforeTheToolBlock`
      （对照组 = 同一回合没人转向，把回显行去掉后逐行相等）
- [x] 调用头在 ≤2 逻辑行 / 160 字符处截断 ——
      `ToolCallBlockTest.aVeryLongSingleLineCallHeaderStopsAtOneHundredSixtyCharacters` /
      `aMultiLineCallHeaderStopsAtTwoLogicalLines`
- [x] 结果预览在 3 **视觉行**处截断 ——
      `ToolCallBlockTest.theResultPreviewIsCappedAtThreeVisualRowsAtEveryWidth`（宽 120 显示 3 源行、宽 40
      显示 1 源行，两者都占 3 视觉行）
- [x] 溢出行文案只有一处常量 —— `render/BlockOverflow.line(int)`；`grep -rn "ctrl+o 查看全部" src/main`
      只命中该文件。`DiffBlock` / `CodeBlock` 的 100 源行上限未动（`DiffBlockTest` / `CodeBlockTest` 全绿）
- [x] 缩放之后同一个工具块按新宽度重画，`… +N 行` 的 N 随之变化且**不留残行** ——
      `ScreenTruthRegressionTest.aToolBlockIsRepaintedAtTheNewWidthAndItsOverflowCountFollows`
      （60 → 40 逐列拖动，N 从 3 变 5，旧的 `… +3 行` 不残留）
- [x] `emitToolResultSummary` 及其 helper 已删除，全仓库无残留调用（连带 `compactOneLine` /
      `isStepSearchResult` 也失去调用方，一并删除）；`web_search` / `web_fetch` 的结果改经统一路径
- [x] 浏览层里工具块展开出**完整**结果 —— `ToolCallBlockTest.theOverlayExpandsTheWholeResult`
      （`renderRetained(EXPANDED)` 含全部 40 行、无溢出行；浏览层对非思考可折叠块本来就选 EXPANDED）
- [x] `StripEntryContractTest` 绿（5/5）：`ToolCallBlock` 仍在被检查的条目里，`render()` 不改变任何字段
- [~] `mvn test -Pphase16-smoke` —— 453 tests / 5 failures，**与 HEAD 基线逐条相同**（全部在
      `InlineRendererTest`，与工具块无关，实测 n=1 于 HEAD 的干净克隆）。本 issue 未引入新失败，但该 profile
      在 HEAD 上本来就不是全绿。
