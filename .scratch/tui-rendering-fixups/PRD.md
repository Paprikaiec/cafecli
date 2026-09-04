# PRD: Inline 渲染修正——折叠改由 Ctrl+O 全屏浏览层，默认流清理

Status: ready-for-agent
Date: 2026-07-18
Branch: feat/tui-rendering-fixups（建议）
Decision records: ADR 0012（折叠交给 Ctrl+O 全屏浏览层，默认流不再原地展开；取代 ADR 0011「Ctrl+O 原地切换」、收窄 ADR 0010 活区职责）
Glossary: `CONTEXT.md`「终端渲染」段（[[活区尾]] / [[冻结回卷]] / [[保留窗口]] / [[转写浏览层]] / [[折叠块]] / [[块色条]]）
Parent: 承接并部分取代 `.scratch/tui-rendering-pipeline/PRD.md`

## Problem Statement

`tui-rendering-pipeline` 交付后，用户在实际使用中反馈了 8 类可感知问题：

1. **Ctrl+Y 无反应**：底部状态栏一直宣传「Ctrl+Y 切换 HITL / YOLO」，但根本没有任何绑定，按了没反应。
2. **Ctrl+O 在默认模式几乎不可用**：无论何时按都回落到「blocked」，被迫改用 Ctrl+T。根因是完成块在答案开始的瞬间就被冻结进[[冻结回卷]]，等用户读到「(ctrl+o to expand)」时它早已无法原地展开。
3. **「blocked」提示直接印进历史**：这条失败提示经 `printAbove` 落进 scrollback、层层堆叠，污染历史。
4. **Ctrl+T 浏览层是 vi 式操作、不能滚轮滑动，且其 Ctrl+O 只展开当前选中行**，与「展开全部」的预期不符。
5. **多余的绿色像素点**：答案附近出现游离的绿色方块。
6. **满屏 emoji**：工具 / 思考折叠头带一堆 emoji 图标，受字体 / 字宽影响错位、观感杂乱。
7. **展开代码里的流程图 / 树形结构线条错位**：盒绘制 ASCII/Unicode 图被折行打散。
8. **排版凌乱**：默认模式和浏览层都存在忽多忽少的空行、莫名其妙的大段空行，以及 `====` / `----` / `---` 分隔线切割段落。

其中 2 与 3 是同一个结构性矛盾的两面：[[冻结回卷]]是终端原生 scrollback、追加式且不可改写，而「原地展开一个已冻结的块」在物理上不可能——ADR 0011 的「Ctrl+O 原地切换最近可折叠块」在流水线中几乎永远命中不到活的块。

## Solution

按 ADR 0012 重构默认流与折叠交互，把折叠语义从「原地」彻底移到「全屏浏览层」：

1. **单一折叠出口**：**Ctrl+O** 打开全屏[[转写浏览层]]并**强制展开所有折叠块**；再按一次 Ctrl+O / `q` / `ESC` 关闭、回到输入。**Ctrl+T 键位退役**（`/transcript` 命令保留为等价入口）。原地折叠 / 展开取消——问题 2、3 随之消失，不再有「blocked」失败态与追加污染。
2. **默认流分级呈现**：思考、工具调用 → 一行带[[块色条]]的**折叠头**（无 emoji）；代码 / diff → **内联**（默认前 100 行，超出标 `… +N 行 (ctrl+o 查看全部)`）；答案逐 token 流式。
3. **浏览层 = 流内容的展开**：复用与默认流相同的块渲染函数（`foldState=EXPANDED`），风格天然一致；可**鼠标滚轮**与按键（j/k/方向键 / PgUp/PgDn / g/G）滚动；作用域 = [[保留窗口]]最近 5 轮。
4. **活区尾收窄**：`LiveRegionStrip` 收窄为单写者最小重绘区（spinner + 答案流式尾 + 审批卡），删除 freeze / 原地 toggle / notice 复杂度；展开态只从[[保留窗口]]源块重建。
5. **Ctrl+Y 补齐**：复用 `/hitl` 通路切换 HITL / YOLO 并刷新状态栏。
6. **视觉清理**：移除游离绿色答案标记、移除 emoji 图标（保留块色条）、代码按宽裁剪不折行、移除 `┌─`/`└─` 与标题下划线 / 主题分隔线、统一「块间恰一个空行」。

## User Stories

### 默认流分级呈现

1. As a 终端用户, 我想让思考过程在默认流里折叠成一行带色条的摘要头, so that 长推理不占屏但仍可追溯。
2. As a 终端用户, 我想让工具调用折叠成一行带色条的摘要头, so that 一批文件读取 / 命令不刷屏。
3. As a 终端用户, 我想让代码块内联展示前 100 行、超出标注 `… +N 行 (ctrl+o 查看全部)`, so that 常见片段一眼看全、超长代码不淹没对话。
4. As a 终端用户, 我想让 diff 同样按 100 行上限内联, so that 大改动不刷屏、小改动一眼看全。
5. As a 终端用户, 我想让答案正文逐 token 流式出现在活区尾, so that 我能实时看到模型在写什么。

### Ctrl+O 全屏浏览层

6. As a 终端用户, 我想按 Ctrl+O 一键进入全屏浏览层并展开所有折叠块, so that 我能一次看全整轮的思考 / 工具 / 代码细节。
7. As a 终端用户, 我想在浏览层再按一次 Ctrl+O（或 q / ESC）退出回输入, so that 看完能立刻继续输入。
8. As a 终端用户, 我想让浏览层的排版风格与默认流完全一致, so that 展开只是「看到更多」、没有视觉断层。
9. As a 终端用户, 我想用鼠标滚轮在浏览层上下滑动, so that 我能像 less 一样自然翻阅而不必记 vi 键。
10. As a 终端用户, 我想在浏览层用 j/k/方向键（行）、PgUp/PgDn（页）、g/G（首尾）滚动, so that 键盘操作也顺手。
11. As a 终端用户, 我想让浏览层展示最近 5 个用户回合, so that 我能回看近期上下文。
12. As a 终端用户, 我不想再有独立的 Ctrl+T 键位, so that 折叠浏览只有一个入口、不用记两个键。

### 折叠交互不再有失败态

13. As a 终端用户, 我不想在按 Ctrl+O 时看到「Block frozen; view via Ctrl+T」, so that 折叠交互不再有失败态。
14. As a 终端用户, 我不想让任何折叠 / 展开提示被追加进 scrollback, so that 历史输出不被状态噪音污染。

### Ctrl+Y 切换 HITL / YOLO

15. As a 终端用户, 我想按 Ctrl+Y 在 HITL 与 YOLO 之间切换, so that 状态栏宣传的快捷键真的可用。
16. As a 终端用户, 我想让 Ctrl+Y 切换后底部状态栏立即反映新模式, so that 我能确认切换生效。
17. As a 终端用户, 我想让 Ctrl+Y 在空闲输入期和任务执行中都能用, so that 我随时能改审批策略。
18. As a 终端用户, 我想让 Ctrl+Y 与 `/hitl on|off` 行为一致（含 approvalPump 同步）, so that 两个入口不产生分叉状态。

### 视觉清理

19. As a 终端用户, 我不想看到答案附近出现游离的绿色方块, so that 屏幕干净、没有像素噪点。
20. As a 终端用户, 我想让工具调用折叠头不含 emoji 图标, so that 界面统一、不受字体 / 字宽影响错位。
21. As a 终端用户, 我想让思考折叠头不含 🧠 emoji, so that 同上。
22. As a 终端用户, 我仍想靠左侧不同颜色的块色条区分块类型, so that 去掉 emoji 后仍能一眼分辨思考 / 工具 / 代码 / diff。
23. As a 终端用户, 我想让展开的流程图 / 树形结构等盒绘制内容保持对齐, so that ASCII/Unicode 图不被折行打散。
24. As a 终端用户, 我想让超宽代码行按终端宽裁剪（尾 `…`）而非 prose 折行, so that 图形的可见部分仍保持网格对齐。
25. As a 终端用户, 我不想让代码块带 `┌─ code` / `└─ end` 边框, so that 代码区更干净。
26. As a 终端用户, 我不想看到标题下的 `====` / `----` 下划线, so that 段落之间不被横线切割。
27. As a 终端用户, 我不想看到 `---` / `———` 主题分隔线, so that 统一用空行分段。
28. As a 终端用户, 我想让不同模块之间统一用恰好一个空行分隔, so that 排版整齐、没有忽多忽少的空行。
29. As a 终端用户, 我不想在列表项之间出现莫名其妙的大段空行, so that 列表紧凑可读。
30. As a 终端用户, 我想让代码块用统一空行进入 / 退出, so that 代码与正文的边界靠空白而非边框表达。

### 架构 / 可维护性

31. As a 维护者, 我想让 `LiveRegionStrip` 收窄为只管活区尾（spinner + 答案尾 + 审批卡）, so that 折叠相关复杂度（freeze / toggle / notice）被移除、代码更好维护。
32. As a 维护者, 我想让展开态只从[[保留窗口]]源块重建, so that 默认流与浏览层是单一数据源的两个视图。

## Implementation Decisions

- **交互模型**：`Ctrl+O` 打开 / 关闭全屏[[转写浏览层]]并强制展开全部折叠块；`Ctrl+T` 键位退役（`/transcript` 命令保留）；删除 `RuntimeControl.TOGGLE_FOLDABLE` 与 `InlineRenderer.toggleLastBlock`。浏览层仍限空闲提示符进入（raw-mode 读键只在主 / 输入线程，遵 ADR 0005）。
- **活区尾**：`LiveRegionStrip` 收窄为单写者最小重绘区（spinner + 答案流式尾 + 审批卡）；保留 `freezeSettled` 作为完成内容提交到不可变冻结回卷的边界，删除 `frozenFoldableAvailable` / `toggleLastFoldable` / `pendingNotice`「Block frozen」/ 原地 `FoldState` 切换。
- **折叠块默认渲染**：思考、工具调用 = 一行折叠头（[[块色条]] + 无 emoji 文案）。代码 / diff = 内联，默认上限 100 行，超出追加一行溢出标注 `… +N 行 (ctrl+o 查看全部)`。
- **转写浏览层**：`TranscriptOverlayModel` 从块光标模型改为行滚动视口 + 进入即全展开；新增**纯函数**解析 SGR（1006）鼠标滚轮事件映射为行滚动，与既有键 `decode` 同构；`TranscriptOverlay` 在 raw + alt-screen 块内开关鼠标追踪；`Ctrl+O` / `q` / `ESC` 关闭。作用域 = [[保留窗口]]最近 5 轮，复用与默认流相同的块渲染函数（`foldState=EXPANDED`）。
- **Ctrl+Y**：新增 ASCII 25 绑定——空闲期为 `LineReader` widget、任务期为 `RuntimeControl.TOGGLE_HITL`；两者调用共享 `toggleHitl()`（复用 `/hitl on|off` 通路：`SwitchableHitlHandler.setEnabled` + `approvalPump` 同步），随后 `renderer.updateStatus(...)`。
- **答案标记**：移除 `Agent` 在流式答案起点打印的绿色 `answerMarker`「▪」；若 `AnsiStyle.answerMarker` 无其他使用者则删除。答案边界由块模型 + 统一空行表达。
- **Emoji**：`ToolCallBlock`（`toolLabel` / `collapsedLabel` / `mcpLabel` 全部图标，`stripPrefixIcon` 变为无用可删）与 `ReasoningBlock`（🧠）去除 emoji；`BlockAccent` 块色条保留。
- **代码渲染**：`CodeBlock` / `MemoizedBlock` 对代码源行**按终端宽裁剪（尾 `…`）**，不再走 `ProseEntityHighlighter.wrap` 的 `columnSplitLength` 折行；移除 4 空格缩进；移除 `┌─`/`└─` 边框；实现 100 行上限 + 溢出标注。`TerminalMarkdownRenderer` 的围栏代码路径（`toggleCodeBlock` 的 `┌─`/`└─`、缩进）同步。
- **Markdown 排版**：`TerminalMarkdownRenderer` 删除标题下划线（`renderHeading` 的 `=` / `-`）；识别 `---` / `***` / `___` 主题分隔线并渲染为单空行；统一块间距为恰好一个空行、块内 / 列表项间 / 引用内不额外空行（`ensureBlockSpacing` / `writeBlankLine` / `needsLineBreakBeforeNextBlock` 归一）；与简化后的回卷空行协调，避免双重空行。
- **数据流不变量**：展开态只从[[保留窗口]]源块重建；默认流与浏览层是同一数据源的两个视图，风格由共享块渲染函数保证一致。

## Testing Decisions

- **好测试标准**：只断言外部可观察行为——渲染出的行序列、键 / 鼠标事件到 Action 的映射、导航 State、状态栏文案。不断言私有字段、调用次数、帧内部结构等实现细节。
- **`TranscriptOverlayModel`**（纯 reducer，最高 seam）：断言 `decode`（含新增 SGR 滚轮）→ Action、`reduce`（行滚动、进入即全展开、PgUp/PgDn、g/G）→ State。Prior art：既有 `TranscriptOverlayModel` decode/reduce 用例、`ExecutionPlanTest` 式纯状态机测试。
- **`TerminalMarkdownRenderer`**：断言输出行序列——无 `===` / `----` 下划线行、`---` → 单空行、块间恰一个空行、列表项间无多余空行、无 `┌─`/`└─` 代码标签。
- **`CodeBlock`**：固定宽渲染，断言超宽行被裁剪（行数不因折行膨胀、尾 `…`）、>100 行出现溢出标注行、无边框、无 emoji。
- **`ToolCallBlock` / `ReasoningBlock`**：断言折叠头不含 emoji 码点、保留块色条与既有标签文本（如「读取 N 个文件」/「思考 N 行」）。Prior art：既有 `InlineRendererTest` / `DiffBlockTest` 的头部子串断言（需同步更新被断言的旧子串）。
- **`Main`**：断言 `classifyRuntimeControl` 对 Ctrl+Y → `TOGGLE_HITL`、旧 `TOGGLE_FOLDABLE` 已移除；`BottomStatusBar.formatStatusLine` 断言 HITL / YOLO 文案随状态切换。Prior art：`MainInputNormalizationTest` / 既有 runtime-control 测试。
- **`InlineRendererTest`**：注入 `PrintStream` + `FramePump` / `FrameTicker`，断言默认流不再产生「Block frozen」文案、不再有游离 `answerMarker`、活区尾只含 spinner + 答案尾 + 审批卡。
- **`DiffBlock`**：断言 100 行上限 + 溢出标注（沿用 `DiffBlockTest`）。
- **回归入口**：`mvn test -Pphase16-smoke`（TUI）+ 针对性 `mvn test -Dtest=... -DskipTests=false`。

## Out of Scope

- 浏览层的**水平滚动 / 超宽代码横向浏览**：本轮只做按宽裁剪 + 纵向滚动。
- **全会话（>5 轮）展开浏览**：[[保留窗口]]仍是 5 轮，更早内容留在终端 scrollback 的折叠形态。
- **任务执行中打开浏览层**：浏览层仍限空闲提示符进入；任务期 Ctrl+O 不进入浏览层。
- 语法高亮语言扩展、OSC 8 超链接、prose 实体高亮：均属既有已交付特性，不在本轮改动。
- MCP OAuth / sampling、审批期 diff 预览（`tui-rendering-pipeline` issue 13 独立）等既有 out-of-scope 不变。

## Further Notes

- 本 PRD 承接并部分取代 `tui-rendering-pipeline`（ADR 0010 / 0011）：ADR 0012 取代「Ctrl+O 原地切换」决策、收窄活区职责。原 issue `04-ctrl-o-in-place-toggle` 的原地折叠语义作废。
- 硬规则联动（AGENTS.md §2「改命令入口 → 联动」+ §1「改行为 → 同步文档」）：本轮需更新 `AGENTS.md` inline-rendering 段——Ctrl+O / Ctrl+T 语义、被断言的头部子串（如 `🧠 思考…` / `┌─`）、分隔线与空行策略；`README.md` 若提及键位同步。
- 已随本设计落地的文档：`docs/adr/0012-fold-via-overlay-not-in-place.md`、`CONTEXT.md`「终端渲染」词汇段。
