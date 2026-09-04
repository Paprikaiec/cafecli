# PRD: TUI 渲染管线重构（活动区合成器 + 类型化块模型）

Status: ready-for-agent
Date: 2026-07-09
Branch: feat/tui-rendering-pipeline（建议）
Decision records: ADR 0010（活动区合成器建在 JLine Display 之上，不 fork、不进主屏 alt-screen）+ ADR 0011（类型化块内容模型与参考架构裁剪）
Glossary: `CONTEXT.md`「终端渲染」段（[[转写区]] / [[活动区]] / [[保留窗口]] / [[转写浏览层]] / [[可折叠块]]）

## Problem Statement

Cafecli inline 模式的折叠交互和渲染管线有四类用户可感知的问题：

1. **Ctrl+O 几乎不可用**：只有工具调用组和围栏代码块会注册为可折叠块，且注册新块会冻结所有旧块、Ctrl+O 永远只命中最后一个——一轮里先有工具调用再有代码块时，工具调用块永久卡在折叠态。思考过程、文件 diff 等内容从来不可折叠（diff 甚至完全绕过 transcript 直写终端）。
2. **展开是追加式污染**：Ctrl+O 触发的重绘走 `printAbove`，把整轮 transcript **重新追加**一份到输入行上方——每切换一次，scrollback 里就多一份重复内容，越切越乱。根因是结构性的：transcript 活在终端原生 scrollback 里（不可改写），而折叠/展开语义要求重绘历史，当前实现用行数估算 + frozen 标记硬撑，估算漂移就是 bug。
3. **流式渲染耗时与闪烁**：四个写者（转写流、thinking 区 ticker 线程、状态栏、主线程审批提示）各自持锁直写 stdout。每个 reasoning delta 触发一次 thinking 区整体清除重建（每次还把整个 4KB 缓冲重新正则切分）；后台 push 输出每行触发一次完整 JLine redisplay；无帧合并、无单帧单次写出。表现为长思考时可见闪烁、CPU 空转。
4. **无法度量**：没有任何渲染统计或基准，「优化耗时」既无基线也无回归防线。

另有正确性隐患：同文件内存在三份互相矛盾的手写字符宽度实现（CJK 宽度判定不一致 = 行数估算漂移的来源）；转写流按 write 块解码 UTF-8，CJK 字符跨块被拆时会乱码。

## Solution

按 ADR 0010 + 0011 重构 inline 渲染管线，一次解决折叠语义与流式耗时：

1. **活动区合成器**：[[转写区]]不可变（写入 scrollback 的内容永不重绘）；底部[[活动区]]（流式输出、活动指示、可折叠块、审批提示、输入行、状态坞）由单渲染线程独占重绘，diff 引擎复用 JLine `Display`（不自建 cell buffer、不 fork JLine）。`printAbove` 从每行热路径降级为活动区顶端的批量冻结刷出。
2. **类型化块模型**：`Renderer` 升级为块结构事件；[[保留窗口]]（最近 5 个用户回合）保存块的**源内容**，按 `(width, foldState)` 渲染为预折行样式行并 memoize——行数即行高，估算算术整体消失；`┌─ code` 字符串嗅探协议删除。
3. **折叠语义架构化**：工具调用组 / 代码块 / 思考引用 / Diff 四类可折叠；在活动区内 Ctrl+O 原地切换（命中输入行上方最近可折叠块）；滚出后经 **Ctrl+T / `/transcript`** 进入[[转写浏览层]]（全屏 overlay，块光标导航，ESC 退出，主屏 scrollback 原样保留）。零重复输出。
4. **帧调度**：单渲染线程 + MPSC 事件队列，~33ms leading+trailing 合帧，每脏帧单次 write+flush，按能力包 BSU/ESU；HITL 审批渲染移到渲染线程、按键读取仍在主线程。
5. **本轮同捆的渲染特性**：升级正则语法高亮（分色 + 跨行状态 + JSON/YAML/shell/SQL/XML）；prose 实体高亮（文件路径 / 函数引用分色）；`path:line` 包 OSC 8 超链接；Diff 块全宽绿/红背景行。
6. **预算制验收**：渲染循环常开计数器 + 确定性风暴基准测试，断言 flush 预算 / O(delta) 工作量 / 零重复字节 / UTF-8 边界正确。

## User Stories

### 折叠 / 展开

1. As a 终端用户, I want 按 Ctrl+O 展开/收起输入行上方最近的可折叠块, so that 我能快速查看工具调用明细或代码全文而不改变肌肉记忆。
2. As a 终端用户, I want 展开/收起在原地重绘、不在屏幕上追加重复内容, so that 我的 scrollback 不被同一轮输出的多份拷贝污染。
3. As a 终端用户, I want 同一轮里的多个可折叠块（先工具调用、后代码块）都能被切换, so that 早出现的块不会因为后出现的块而永久卡死在折叠态。
4. As a 终端用户, I want 思考过程在回合结束后折叠为一行摘要、可展开回看, so that 长推理不占屏但仍可追溯。
5. As a 终端用户, I want write_file/edit_file 的 diff 以折叠头 `± path (+N/-M)` 呈现、展开看完整 hunks, so that 大改动不刷屏、小改动一眼看全。
6. As a 终端用户, I want 超过阈值（约 10 行）的代码块默认折叠、短代码块直接展示, so that 常见小片段零交互、大片段不淹没对话。
7. As a 终端用户, I want 滚出活动区的块明确进入冻结态（提示经 Ctrl+T 查看）, so that 我理解为什么原地切换只对最近内容生效。

### 转写浏览层

8. As a 终端用户, I want 按 Ctrl+T（或输入 `/transcript`）进入全屏转写浏览层, so that 我能回看最近 5 个回合的完整输出包括已冻结的块。
9. As a 终端用户, I want 在浏览层用 j/k/方向键移动块光标、Enter 或 Ctrl+O 切换选中块、PgUp/PgDn 翻页、g/G 跳首尾、q/ESC 退出, so that 展开三轮前的某个工具调用只需两次按键。
10. As a 终端用户, I want 退出浏览层后主屏和 scrollback 与进入前完全一致, so that alternate screen 的使用对我的终端历史零副作用。
11. As a 终端用户, I want 回合运行中按 Ctrl+T 得到一行提示（空闲后可用）而非黑屏或错乱, so that 流式期间的误按无害。
12. As a 终端用户, I want `/clear` 同时清空保留窗口与折叠状态, so that 「清空会话视图」的语义保持一致。

### 流式渲染与耗时

13. As a 终端用户, I want 长思考流式期间 thinking 区平滑更新、无整屏闪烁, so that 观感稳定、终端 CPU 不空转。
14. As a 终端用户, I want 流式输出以合帧节奏（约 30fps）呈现而不是每个 delta 抖一次, so that 快速输出时依然流畅。
15. As a 终端用户, I want 我正在输入时后台任务的 push 输出按帧批量出现在输入行上方, so that 输入行不因每行输出被反复重绘打断。
16. As a 终端用户, I want 支持 synchronized output 的终端上整帧原子呈现（BSU/ESU）, so that 不出现半帧撕裂。
17. As a 终端用户, I want 终端窗口 resize 后活动区内容按新宽度重排, so that 折行不错乱。
18. As a 终端用户, I want CJK 文本在任意流式分块边界下渲染正确, so that 中文输出永不乱码。
19. As a 终端用户, I want HITL 审批提示出现在活动区内、回车响应逻辑与现在一致, so that 审批体验不因渲染重构改变。

### 语法与实体高亮

20. As a 终端用户, I want 代码块按语言高亮：关键字/字符串/注释/数字/注解分色, so that 代码可读性接近编辑器。
21. As a 终端用户, I want JSON、YAML、shell、SQL、XML 这些 agent 高频输出的格式也有高亮, so that 配置和命令输出同样易读。
22. As a 终端用户, I want 跨行结构（块注释、三引号字符串）高亮正确, so that 多行结构不因逐行处理而错色。
23. As a 终端用户, I want 正文中提到的文件路径（含 `path:line`）以统一颜色凸显, so that 我能一眼定位 agent 引用了哪些文件。
24. As a 终端用户, I want 正文中带括号的函数/方法引用（如 `executeTools()`、`Agent.run()`）以另一颜色凸显, so that 代码符号在叙述里可扫读。
25. As a 终端用户, I want 在 iTerm2 等支持 OSC 8 的终端里 `path:line` 可 Cmd+点击直达文件, so that 从对话跳转到代码零摩擦。
26. As a 终端用户, I want 不支持 256 色/OSC 8 的终端自动回退到基础前景色样式, so that 老终端不显示乱码序列。

### Diff 展示

27. As a 终端用户, I want diff 的新增/删除行以全宽绿/红**背景**呈现, so that 改动块一眼可辨（对齐 Claude Code 观感）。
28. As a 终端用户, I want diff 仍按 hunk 组织并带 `@@` 头与上下文行, so that 我能定位改动在文件中的位置。

### 兼容与维护

29. As a headless/CI 使用者, I want `run --file` 与 `CAFECLI_RENDERER=plain` 路径行为完全不变, so that 无人值守与降级路径零回归。
30. As a 维护者, I want 新块事件在 `Renderer` 接口上有 default 实现（落回 stream() 线性文本）, so that PlainRenderer / LanternaRenderer / spawner no-op renderer 零改动兼容。
31. As a 维护者, I want `-Dcafecli.render.stats=true` 输出帧/写/字节/flush/合并/缓存命中统计, so that 渲染性能可观测、可复核。
32. As a 维护者, I want 确定性预算基准测试常驻回归, so that 未来改动无法悄悄退化为逐 delta 重绘或重复输出。
33. As a 维护者, I want `┌─ code` 字符串嗅探协议被块事件取代, so that 渲染结构不再靠正则逆向自己的装饰输出。
34. As a 维护者, I want 字符宽度只有 JLine 一个权威实现, so that 不再有三份互相矛盾的宽度函数导致行数漂移。

## Implementation Decisions

**架构（ADR 0010）**

- 活动区合成器：转写区不可变；活动区 ≤ 可视区，由单渲染路径独占重绘；内容滚出活动区顶端即冻结刷出（批量 `printAbove`，每帧一次）。
- diff 引擎复用 `org.jline.utils.Display`（approach A：渲染线程自持 Display 实例驱动活动区条带，与 LineReader 的 display 栈式共存）；不 fork JLine、不 subclass `LineReaderImpl`（后者保留为 typing 态抖动时的升级路径）。
- 条带合成仅在 readLine 非活跃时进行；用户输入期只有后台 push 输出，按帧批量 `printAbove` 追加。
- 转写浏览层用 `Display(terminal, fullScreen=true)` 渲染，alternate screen 仅用于浏览层。

**内容模型（ADR 0011）**

- `Renderer` 接口升级为类型化块事件（begin/append reasoning、code、tool calls、diff、answer、user prompt）；`stream()` 保留为非结构化文本块入口；新方法给 default 实现落回 `stream()`。
- 保留窗口 = 最近 5 个用户回合的 `Turn → List<Block>`；块保存源内容（raw markdown/text），按 `(width, foldState)` 渲染为预折行 `AttributedString` 行并按块 memoize；行数即行高。
- `beginTurn` 不再清空 transcript 模型（改为保留窗口滚动淘汰）；`/clear` 清空保留窗口与折叠状态。
- 不建通用节点树、不引 Yoga/flexmark/tm4e/tree-sitter/ICU4J/Caffeine（理由与替代记录在 ADR 0011）。

**帧调度与线程**

- 单渲染线程独占全部终端写出；producer（回合执行线程、任务虚拟线程、spinner tick、status 更新）投事件进 MPSC 队列（LinkedBlockingQueue + dirty flag 足够）。
- ~33ms leading+trailing 合帧；spinner 独立 250ms 节奏；每脏帧单次 write+flush，按能力探测包 BSU/ESU（`ESC[?2026h/l`）。
- HITL：审批 UI 作为活动区元素由渲染线程绘制；按键读取仍在主线程（「读终端只在主线程」不变；队列单向 + future 回传，与 ApprovalPump 同构）。
- 帧触发抽象可注入（测试手动泵帧，不依赖真实调度器）。

**折叠与交互**

- 可折叠：工具调用组 / 代码块（>~10 行默认折叠）/ 思考引用（回合结束折叠为一行摘要）/ Diff（折叠头 `± path (+N/-M)`）；答案正文与用户输入不可折叠。
- Ctrl+O = 切换活动区内输入行上方最近可折叠块；Ctrl+T + `/transcript` = 转写浏览层（牺牲 emacs transpose-chars）；浏览层内 j/k/方向键移块光标、Enter/Ctrl+O 切换、PgUp/PgDn/Space 翻页、g/G 首尾、q/ESC 退出；仅空闲提示符可进，回合中 Ctrl+T 打一行提示。
- `/transcript` 命令按 AGENTS.md 硬规则 #2 联动：Main + CliCommandParser + 测试 + README + AGENTS.md。

**渲染特性**

- Markdown：沿用自研 `TerminalMarkdownRenderer`，输出从写 PrintStream 重构为产出样式行；AnswerSegment 用 append-only 行缓存（完成行渲染一次即缓存，仅尾行每帧重渲）。
- 语法高亮：升级现有正则 `CodeHighlighter` 并迁出 `tui/` 到渲染中立包；产出样式 span；关键字/字符串/注释/数字/注解分色；跨行状态（块注释/三引号）；新增 JSON/YAML/shell/SQL/XML；在完成行上执行一次进缓存。
- 实体高亮：prose 行后处理（`AttributedStringBuilder.styleMatches`）；文件路径 = 含 `/` 或已知代码扩展名的 token（可带 `:line` 后缀），一色；函数引用 = 要求尾随括号（`name()` / `Class.method()` / `Class#method()`），另一色；作用于是否在反引号内均生效。
- OSC 8：`path:line` 匹配包 `file://` 超链接，经 `TerminalCapabilities` 门控，不支持时纯颜色。
- Diff 块：全宽绿/红背景行（行 pad 到宽度），hunk 头青色；256 色以下回退现有前景色样式；行内变化 span 强调为后续可选优化。
- 宽度权威：JLine `AttributedString.columnLength`/WCWidth 唯一；删除三份手写宽度实现；ZWJ emoji 簇宽度接受为已知边界。

**落地方式**

- 直接替换 inline 管线，不留 legacy 旗标；`CAFECLI_RENDERER=plain` 是既有逃生通道。
- 切片顺序：管线（单写者渲染循环 + 块模型 + 冻结刷出）先行，特性层（高亮 / OSC 8 / 浏览层 / Diff 背景样式）后置——特性全部落在块渲染函数上。
- 文档联动（AGENTS.md 硬规则 #1）：AGENTS.md「启动与 inline 渲染当前约定」段重写、README、CONTEXT.md 已更新。

## Testing Decisions

好测试 = 只断言外部行为：给定块事件序列 / LLM delta 序列，断言**终端输出字节**与**预算计数器**；不断言内部数据结构。

**测试缝（自高向低）**

1. `Renderer` 块事件接口：喂块事件 → 断言捕获的终端字节（折叠形态、切换零重复、样式序列存在性）。
2. `InlineRenderer(terminal, out)` 注入 PrintStream + Mockito mock `Terminal`：字节级捕获与终端尺寸控制（reflow 测试）。既有先例：phase16-smoke 的 render/inline 测试族。
3. `Agent.StreamRenderer` ← `LlmClient.StreamListener`：喂 reasoning/content delta → 断言块事件序列正确（嗅探协议消亡的回归锚）。
4. 注入式帧泵：手动泵帧做确定性合帧断言，无 sleep、无 flaky。
5. `RenderStats` 计数器：预算断言的读取缝，同时是 `-Dcafecli.render.stats` 产品特性。
6. 纯块渲染函数 `Block.render(width, foldState)`：高亮、实体模式、OSC 8、diff 背景、markdown 行、折叠头全部无终端单测。
7. 浏览层 model/key 分离：光标与折叠状态迁移为纯函数测试，按键循环保持薄壳（先例：PlanReviewInputParser 与 raw 循环的分离）。

**预算断言（风暴基准，进常规回归）**

- 模拟风暴（约 5k reasoning delta + 500 content 行 + 20 工具事件）下：flush 次数 ≤ 脏帧数 + 常数（每脏帧恰一次写出）。
- 逐 delta 工作量 O(delta)：不存在整缓冲重切分（计数器验证）。
- Ctrl+O 切换输出零转写重复字节（mock 终端捕获，断言该轮内容恰出现一次）——原始 bug 的永久回归锚。
- UTF-8 分块边界：CJK 跨 write 拆分渲染不乱码。

**手工验收**：`mvn clean package` 出 jar，iTerm2 + Terminal.app 长思考回合无闪烁、折叠切换无残影、Ctrl+T 浏览层进出 scrollback 无损。

## Out of Scope

- **审批时 diff 预览**（HITL 链改动，AGENTS.md §5.4 联动面）——follow-up issue，复用本轮 DiffBlock 渲染组件。
- 回合运行中进入转写浏览层（暂提示空闲后可用）。
- ICU4J / ZWJ emoji 簇宽度；tm4e / tree-sitter 高亮；Yoga 布局引擎；flexmark。
- GraalVM native-image / jlink 分发（参考材料第 8 层，独立课题）。
- fork JLine 或 subclass `LineReaderImpl`（approach B 仅作为 typing 态抖动的记录在案升级路径）。
- Lanterna 全屏 TUI（legacy，零改动）；鼠标追踪（SGR 1003）与拖拽选区；4ms 滚动快速通道。
- diff 行内变化 span 强调（可选后续优化）。

## Further Notes

- 参考材料 8 层与本轮的映射：L1 终端 I/O = JLine 4 已有 + 手写 BSU/ESU、OSC 8 常量；L2–3 节点树/布局 = 类型化块列表替代（否决通用树与 Yoga）；L4–5 cell buffer/diff = 复用 JLine `Display` 行级 diff；L6 帧调度 = 采纳（33ms 单写者 + 虚拟线程 producer）；L7 Markdown/高亮 = 沿用自研并升级（否决 flexmark/tree-sitter）；L8 分发 = 出本轮范围。
- `InlineDiffRenderer` 的 LCS/hunk 算法保留复用，仅渲染面升级为背景色块并纳入块模型（修复其绕过 transcript 直写的问题）。
- `FoldableBlock` / `BlockRegistry` / `InlineActivityDisplay` / `ToolCallRenderer` 的职责被块模型 + 条带合成器吸收，随管线切片退役。
- 相关既有测试（FoldableBlockTest / BlockRegistryTest / BottomStatusBarTest 等 phase16-smoke 名单）随退役类同步替换，pom 的 phase16-smoke includes 需更新。
