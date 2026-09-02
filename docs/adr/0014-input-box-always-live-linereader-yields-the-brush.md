# 输入框常驻：LineReader 交出画笔，活区单写者扩展到输入行（修订 ADR 0010）

交互期一直有两套互斥的终端归属：空闲时 JLine `LineReader.readLine()` 持输入与画笔，回合运行时主线程改用裸 raw-mode 轮询（`Main.pumpWaitUntilIdle` + `readRuntimeControl`）。三个症状同源——回合期间键入的字符被 `readRuntimeControl` drain 丢弃；`enterRawMode()` 不关 `ISIG` 而回合期间没有 `readLine` 装 SIGINT handler，Ctrl+C 直接杀进程；Ctrl+O 在回合期间只打一行提示。`LiveRegionStrip.renderFrame()` 还按「LineReader 是否在读」在两条渲染路径间静默切换（读→`printAbove` 只追加不重绘；不读→`Display` 重绘）。

## 决策

**[[输入框]]常驻，[[活区]]的单写者边界扩展到输入行本身。**

`LineReader` 常驻 `readLine`（兑现 ADR 0005 承诺的「主线程退化为输入泵、常驻 readLine」），但**不再自己画终端**：

1. `CaptureDisplay extends org.jline.utils.Display` 覆写 `update(List, int, boolean)`，把「要显示的行 + 光标位置」交给渲染循环而非写终端。JLine 4.0.0 里 `Display` 的**全部**终端写入都汇聚到该方法（`resize` / `reset` / `clear` 不写），`Display` 是 public 非 final。
2. `CafeCliLineReader extends LineReaderImpl` 覆写 `redisplay(boolean)`——重装 `CaptureDisplay` 后 `super.redisplay(flush)`。`redisplay` 的全部私有助手（`insertSecondaryPrompts` / `addRightPrompt` / `displayRows` / 小终端偏移数学）原样复用，**零行方法体拷贝**。补全菜单、次级提示符、右提示符、高亮因此自动进入合成帧。
   **private `doDisplay()` 有四个调用点，重装守卫只覆盖三个**（Wave 0 spike 实测）：构造（:370）、`readLine` 起始（:681）、WINCH（:1280）都在 `redisplay` 之前，守卫够用；第四个在 `redisplay:4202`——JLine `Status` 尺寸变化时**在 `super.redisplay()` 内部**、守卫跑完之后重建 `display`，把 `CaptureDisplay` 换掉。实测未处理时该次按键的提示符 + buffer 直接画进终端、零 capture 帧。修法：在覆写顶部同步继承来的 `protected int lastStatusSize`，使该分支不可达（实测泄漏 1 → 0）。被跳过的分支只做 `puts(CR)` + `doDisplay()`，后者的非 `Display` 副作用仅 `size.copy(terminal.getBufferSize())`，而 `size` 在 `readLine` 起始与 WINCH 仍会刷新（JLine 自身契约），无损失。决策 4 把状态坞移出 `Status` 后 `getStatus(terminal,false)` 返回 null，该分支按构造不可达——同步 `lastStatusSize` 是纵深防御，不是唯一依赖。
   `LineReaderBuilder` **不可用**：它是 final、私有构造，`build()` 硬编码 `new LineReaderImpl(...)`。必须手工构造子类并逐项配置（completer / highlighter / history / parser / expander / completionMatcher / options 都是 public setter；**`variables` 只能走构造器**，`INPUT_RC_FILE_NAME` 在构造期即被读取）。`Main` 与 `RenderReproDriver` 两处建构点都要改。
3. 另外覆写 `printAbove`（改投[[冻结回卷]]队列）与 `doCleanup`（免掉直写的 `println()`）；重绑 `clear-screen`。
4. 底部状态坞离开 JLine `Status`：`super.redisplay` 会在**输入线程**调 `status.redraw()`，与渲染线程争终端；`Status` 还持有自己的 `MovingCursorDisplay` 直接写终端（实测每次 `update()` ~37 字符），是 `LineReader` 截获管不到的第二个画者。取**根本不创建** `Status`（`BottomStatusBar` 不再调 `getStatus(terminal)`；JLine 自身只调非创建版 `getStatus(terminal,false)`，故它恒为 null），状态坞改由渲染线程作为活区行画出。这比「创建后挂起」更彻底：挂起只让 `redraw()` 成 no-op（`Status.java:182`），`size()` 仍返回原行数、继续占 `displayRows` 的预算（Wave 0 实测）。
5. `LiveRegionStrip` 的 `printAbove` 分支**删除**：读者恒在读，该分支会吞掉整个活区。冻结统一走 `Display` 路径。

由此派生三条同源结果——用户意图从此只有一个入口：

- **[[转向消息]]**：回合运行中提交的输入注入当前回合（`Agent.java:275` 迭代边界，与 LSP 诊断 / hook context 同一前置注入通道），不排队到下一回合；回合先结束则退化为下一回合的普通提交，不得静默丢失。
- **[[审批应答]]**：HITL 审批经输入框提交。`InlineApprovalPrompter` 的 `enterRawMode` + `reader().read()` 与常驻 `readLine` 抢同一个 `NonBlockingReader`，必须退役。
- **[[取消键]]**：ESC / Ctrl+C 成为普通键绑定。Ctrl+C 杀进程随之自愈——`readLine` 常驻即 JLine 的 `Signal.INT` handler 常驻（`LineReaderImpl.java:676`），中断以 `UserInterruptException` 抛出且携带 `getPartialLine()`，已键入文本得以保留。`readLine` 之间的空档另设会话级 INT handler 兜底。

## 被否决的方案

- **回合期 typeahead 缓冲（渲染循环自画简化编辑器，回合结束把缓冲 seed 给 `readLine`）**：改动最小、保住现有审批与单写者，但回合期间没有补全 / 历史 / 高亮，且同一个框要由两套代码画，必然视觉漂移。否决理由是它把「输入框任何时刻形态一致」降级成「看起来差不多」。
- **渲染循环自研行编辑器、`LineReader` 退役**：丢掉 `CafeCliCompleter` / `CafeCliHighlighter` / `CafeCliHistory` 的接线与 JLine 的编辑机制（kill ring、词移动、多行、bracketed paste、keymap）。
- **给 `StripEntry` 渲染加锁让浏览层实时跟随**：不让输入线程与渲染线程共享块级锁。
  （原文此处引「ADR 0015 的浏览层快照决定」，但 ADR 0015 从未包含这样一条决定，是一处失效引用。
  实时跟随后来由 ADR 0010 修订 A13 交付，**两种守卫都没有采用**；这条否决所守的不变式改由
  ADR 0016 D1 承担——条目只暴露不可变源，排版与缓存归视图，因此无锁可加。）

## 后果

- **ADR 0010 对 approach B 的定价被修正**。0010 估「覆写 `redisplay` 即抄 ~200 行方法体 = 单方法 mini-fork」，前提是要改 `redisplay` **算什么**；实际只需换它**写到哪**。
- **与 JLine 版本耦合的是六个点，不是一个**（Wave 0 spike 澄清）：`LineReaderImpl` 非 final 且构造器 public、`display` protected、`lastStatusSize` protected、`redisplay(boolean)` protected、`doCleanup(boolean)` protected、`Display.update(List,int,boolean)` public 且是唯一写入者（其余写入成员非 public）。4.0.0 全部成立。守卫方式：`super.redisplay()` 返回后断言 `display == capture`——JLine 升级若改变 `doDisplay()` 的调用位置，测试立刻红，而不是退化成静默双画。
- **残留写入不为零，但不画东西**。实测一次完整 `readLine` 共 20 次 write / 89 字符，`Display.update` 贡献 0；其余全部是终端**模式**位（keypad_xmit/local、bracketed paste on/off、`trackMouse` 无条件发的 49 字符 mouse-disable）与裸 CR。它们不定位光标、不吐字形，故不构成第二个画者。`doCleanup` 的换行与 `printAbove` 的直写是仅有的两处会画的残留，均可覆写消除（实测有效）。`LineReaderImpl` 的 `print` / `println` 是 package-private，故换行只能经 `doCleanup(boolean)` 摘除。
- **双冻结路径消失**，`answersSeparated` / `isFullyPrintedWithContent` 与 `isFullyFrozen` 的重复随之删除——AGENTS.md 记载的 drain 时序空行不一致 bug 类被架构性消灭。
- **`reservedRows()` / `bindReservedRows` seam 消失**，DECSTBM 滚动区不再由 `Status` 维护；活区靠既有的 clear→print→recreate 纪律自保。
- **最不可测的部分变成最可测的部分**。今天 `Main.java:986` 写着「⚠️ 终端侧逻辑，仅手测」，输入行由 JLine 直写真实终端、无法断言；改后输入框以 `lines + cursorPos` 汇入 `LiveRegionStrip.renderFrame()`，而它**本就返回整帧字符串**——输入框、活区尾、状态坞、光标位置可在无终端下一并断言。
- **活区固定成本上升**：输入框 3 行 + 状态坞 2–3 行 = 常驻 5–6 行。短终端（< ~20 行）退化为单条上横线，< ~12 行去掉横线只留 `> `。
- **[[快照]]保真度变粗**：`runTurn("react", turnInput, …)` 锚的是回合原始输入，被转向过的回合与该标签不再完全对应。这是把 ADR 0005 已接受的 effort 级回退再放宽一档，不是新代价。
- 编辑态标记 `* ` 退役，编辑与提交都用 `>`；两者靠位置（框内 / 回卷）与暗色区分，AGENTS.md 相应条目需同步。
