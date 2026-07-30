# 渲染内容模型用类型化块列表，砍掉参考架构的节点树 / 布局引擎 / 第三方 Markdown 与高亮

ADR 0010 定了活动区合成器的边界后，还需要一个内容模型把 Agent 产出变成 `Display` 需要的行列表。现状是「字符串嗅探协议」：`TerminalMarkdownRenderer` 画出 `┌─ code:` 装饰行，`InlineRenderer` 再从 ANSI 流里正则识别回块结构——结构在上游被销毁、在下游被逆向。参考的 8 层架构建议自研 retained 节点树（Box/Text）+ flexbox 布局（Yoga）+ 第三方 Markdown（flexmark）与高亮（tm4e/tree-sitter）。

## 决策

**类型化块列表（typed block list），不建通用节点树**：`Renderer` 接口升级为块结构事件（begin/append reasoning、code、tool calls、diff、answer……），保留窗口模型为 `Turn → List<Block>`（UserPrompt / AnswerSegment / Reasoning / CodeBlock / ToolCallGroup / Diff）。块保存**源内容**（raw markdown/text，非预渲染 ANSI），按 `(width, foldState)` 渲染为预折行的 `AttributedString` 行并 memoize——行数即行高，`estimateRows` 一类估算算术整体消失。条带、转写浏览层、resize 重排全部从同一模型渲染。`stream()` 保留为「非结构化文本块」入口；新块方法给 default 实现（落回 `stream()` 线性文本），`PlainRenderer` / `LanternaRenderer` / spawner no-op renderer 零改动兼容，直接替换 inline 管线、不留 legacy 旗标。

配套裁剪与自研范围：

- **布局引擎：无**。终端条带布局 = 垂直堆叠 + 按宽折行；表格已由 Markdown 渲染器内部解决。Yoga（JNI）为不存在的横向排版需求引入原生依赖，否决。
- **Markdown：沿用自研 `TerminalMarkdownRenderer`，不引 flexmark**。输出从「写 ANSI 到 PrintStream」重构为「产出带样式行」；AnswerSegment 用 append-only 行缓存（完成行渲染一次即缓存，仅尾行每帧重渲）——块对象本身就是缓存，不引 Caffeine。
- **高亮：升级现有正则 `CodeHighlighter`，不引 tm4e/tree-sitter**。jtreesitter 需要 Java 22 FFM，与 Java 21 运行下限冲突；tm4e 拖 Eclipse 平台件。升级项：迁出 `tui/`、产出样式 span、关键字/字符串/注释/数字/注解分色、跨行状态（块注释/三引号）、补 JSON/YAML/shell/SQL/XML。高亮在完成行上执行一次进缓存，热路径零开销，不需要异步 Suspense 机制。
- **正文实体高亮**：prose 行后处理（`AttributedStringBuilder.styleMatches`），保守模式匹配文件路径（含 `/` 或已知扩展名，可带 `:line`）与函数引用（要求尾随括号）；`path:line` 按能力探测包 OSC 8 超链接（`file://`）。
- **宽度权威：JLine（`AttributedString.columnLength`/WCWidth）唯一**，删除现存三份手写宽度实现；不引 ICU4J（约 13MB 只为 ZWJ emoji 簇宽度，接受为已知边界）。
- **帧调度**：单渲染线程独占全部终端写出（producer 投事件进 MPSC 队列），~33ms leading+trailing 合帧，每脏帧单次 write+flush、按能力包 BSU/ESU；HITL 审批**渲染**移到渲染线程、按键**读取**仍在主线程（「读终端只在主线程」不变，队列单向 + future 回传，与 ApprovalPump 同构不死锁）。
- **验收：预算断言而非墙钟**。渲染循环常开计数器（帧/写/字节/flush/合并事件/缓存命中，`-Dcafecli.render.stats=true` 输出），确定性风暴测试断言：每脏帧恰一次 flush、逐 delta 工作量 O(delta)、Ctrl+O 切换零转写重复字节、UTF-8 分块边界不产生乱码。

## 被否决的方案

- **通用节点树 + flexbox 子集 / Yoga**：为只会纵向堆叠的客户建布局引擎。
- **flexmark**：换来 agent 输出极少触达的 GFM 边角，代价是重写已满足 AGENTS.md 表格列宽约定的表格逻辑外加新依赖。
- **tm4e / jtreesitter**：运行时下限冲突（Java 22 FFM）或重量级平台依赖，藏在渲染任务里的隐性门槛提升。
- **行级保留（无块身份）**：折叠/展开需要块身份，直接与需求矛盾。
- **16ms 帧率 / 4ms 滚动快速通道**：条带无滚动视口，30fps 对终端流式已过剩下限。

## 后果

- 折叠种类表落在块渲染函数上：工具调用组 / 代码块 / 思考引用 / Diff 可折叠（Diff 升级为全宽绿/红背景行、256 色以下回退前景色），正文与用户输入不可折叠。
- **审批时 diff 预览是 follow-up issue**：DiffBlock 渲染组件共享，但 before/after 进 `ApprovalRequest` 的管道属 HITL 链改动（AGENTS.md §5.4 联动面），不进本轮关键路径。
- `Renderer` 块事件是新的稳定接口面：后续渲染特性（高亮语言、实体模式、OSC 8 目标）都应落在块渲染函数内，不再触碰管线结构。
- 交互键位：Ctrl+O = 切换条带内最近可折叠块（肌肉记忆不变）；Ctrl+T + `/transcript` = 转写浏览层（块光标导航，仅空闲提示符可进，牺牲 emacs transpose-chars）。
