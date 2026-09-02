# PRD: 常驻输入框 —— 活区单写者扩展到输入行

Status: ready-for-agent
Date: 2026-08-03
Branch: TBD（建议 `feat/always-live-input-box`）
Decision record: ADR 0014（输入框常驻 / LineReader 交出画笔，修订 ADR 0010）、ADR 0015（退役 Plan-and-Execute 与 Lanterna TUI，ADR 0004 续篇）、**ADR 0016（destructive resize epoch 给输入框留下的六个未决项 D1–D6）**；尊重 ADR 0005（事件循环 / 附着·游离）、ADR 0011（typed block content model）、ADR 0013（浏览层折叠思考 + Ctrl+T）

> **本 PRD 写于 issues 01–06 落地之前，destructive-resize-epoch（ADR 0010 修订 A1–A19）随后整体落在它上面。**
> 凡涉及[[转写浏览层]]的段落已按 A12/A13/A14 与 ADR 0016 重写；仍带「快照 / 挂起协议 / 审批不关层 /
> 实时跟随已否决」字样的旧描述如有残留，以 ADR 0016 为准。

## Problem Statement

交互期的终端归属现在是**两套互斥的regime**，按「当前有没有回合在跑」切换：空闲时 JLine `LineReader` 持有输入与画笔；回合运行时主线程改用裸 raw-mode 字节轮询。用户能直接撞到三件事：

1. **模型输出时键入的字符被吞掉。** 回合期间读键的是一个只认 ESC / Ctrl+O / Ctrl+Y 的轮询器，其余字节被 drain 丢弃。用户看着模型跑偏、想立刻纠正，只能等它跑完——中途敲的每一个字都石沉大海，且没有任何反馈说明它被丢了。
2. **Ctrl+C 不是「停下这次输出」，是「杀掉 Cafecli」。** raw mode 不关 `ISIG`，而回合期间没有 `readLine` 在跑、JLine 的 SIGINT handler 没装，于是 Ctrl+C 直接终止进程——正在跑的回合、会话历史、内存级[[任务]]全部蒸发。用户的肌肉记忆（其他 CLI 里 Ctrl+C = 中断当前操作）在这里代价极高。
3. **输出期间进不了[[转写浏览层]]。** 想看被折叠的思考 / 工具调用的完整内容，只能等回合结束——而这恰恰是最想看的时刻。现在按 Ctrl+O 只会打印一行「空闲时可用」。

三个症状同源：**输入行不是[[活区]]的一部分**。它在空闲时由 JLine 画、回合期间根本不存在，于是「回合期间的键盘」和「回合期间的屏幕底部」都没有主人。更深一层，`LiveRegionStrip` 还按「LineReader 是否在读」在两条渲染路径间静默切换（在读 → `printAbove` 只追加不重绘；不在读 → `Display` 重绘），这条隐式分叉是 AGENTS.md 里那段 drain 时序空行 bug 的结构性来源。

同时，`/plan` 是仓库里**唯一**还自持终端输入的路径（占主线程 + raw mode 轮询 ESC + 单键读评审决定 + 嵌套 `readLine`），它和「输入框常驻」正面冲突；Lanterna 全屏 TUI（`tui/`）是同一交互模型的第二份实现，且是另一个 `PlanExecuteAgent` 消费者。

## Solution

**让[[输入框]]常驻，把[[活区]]的单写者边界扩展到输入行本身。**

从用户视角：

- 屏幕底部**永远**有一个输入框——两条横线夹一行 `> `，空闲、模型流式输出中、等待审批时形态一致，不再随回合出现或消失。
- **输出期间键入不再丢失**：字符照常出现在框里，可以正常编辑。按 Enter 提交的内容作为[[转向消息]]**注入当前回合**（在下一次 LLM 调用前生效），不是排队等本回合跑完；提交瞬间就在转写里留下一个带暗色底纹的 `›` 块，标出它落在回合的哪个位置。
- **Ctrl+C 变成「停下这次输出」**：取消当前前台回合与仍[[附着]]的[[任务]]，会话与历史完好，输入框里已经敲的字原样保留。只有在无回合可停、且输入框已空时连按两次才退出 Cafecli。ESC 单一职责——只做取消，空闲时无操作。
- **输出期间可以按 Ctrl+O 进[[转写浏览层]]**看展开的内容。进去看到的是一份进入瞬间的**渲染快照**，回合在背后照常跑；想看最新的就退出重进。
- **HITL 审批经输入框应答**（`y` / `n` / `a` + Enter），与普通提交同一通路；空提交表示「再问一次」，重新呈现审批卡而不误判为拒绝。右提示符随状态切换（`message / @path / @image` → `steer · esc 停止` → `y / n / a`），让这条通路可被发现。
- **`/plan` 与 Lanterna TUI 移除**。`/plan` 打印墓碑说明而非落进「未知命令」。

技术形态（ADR 0014）：`LineReader` 常驻 `readLine`，但**不再自己画终端**——`CaptureDisplay` 截获 `Display.update`，把「要显示的行 + 光标位置」交给渲染循环合成进同一帧。输入行由此从「第二个写者」降级为「又一个生产者」，多生产者单写者的不变式**范围扩大、约束不变**。

## User Stories

1. As a Cafecli 用户, I want 模型流式输出时我键入的字符出现在输入框里, so that 我不用等它跑完才能开始打字。
2. As a Cafecli 用户, I want 输出期间按 Enter 提交的内容立刻注入当前回合, so that 我能在它跑偏时纠正它，而不是等一个我已知是错的回合跑完。
3. As a Cafecli 用户, I want 转向消息提交时就在转写里留下带 `›` 的块, so that 我事后能看出这条纠正落在回合的哪一步。
4. As a Cafecli 用户, I want 回合在下一个迭代边界到来前就结束时我的转向消息自动变成下一回合的提交, so that 我的输入永远不会静默消失。
5. As a Cafecli 用户, I want Ctrl+C 停止当前输出而不是杀掉进程, so that 我不会因为肌肉记忆丢掉整个会话。
6. As a Cafecli 用户, I want Ctrl+C 取消时保留我已经敲进输入框的文字, so that 「停下来，然后按我说的做」是一个连续动作。
7. As a Cafecli 用户, I want 输入框已空且无回合在跑时连按两次 Ctrl+C 才退出, so that 我不会误触退出。
8. As a Cafecli 用户, I want ESC 只做取消, so that 我不用记「ESC 现在是取消还是清空」。
9. As a Cafecli 用户, I want 取消只影响当前前台回合与[[附着]]的任务, so that 我后台派的活不会被顺手杀掉。
10. As a Cafecli 用户, I want 模型输出时按 Ctrl+O 就能进[[转写浏览层]], so that 我能在它正在做事的时候读完整的思考与工具调用。（**已交付**，ADR 0010 修订 A13）
11. As a Cafecli 用户, I want 我向上翻阅之后新输出不会把视口推走、回到末尾又自动跟随, so that 我读到一半不会被顶走，而想看最新时也不用退出重进。（**已交付**，A13；原文写的是「内容保持稳定」，那是快照形态的说法，已被实时跟随取代）
12. As a Cafecli 用户, I want 浏览层里的流式答案随模型输出实时长出来, so that 回合中开层看到的就是**现在**在发生什么。（**已交付**，A13；取代原「底部提示退出重开看最新」）
13. As a Cafecli 用户, I want 浏览期间有[[审批应答]]到达时浏览层自己关掉, so that 我直接落在卡片与回答它的[[输入框]]前面，而不是把一个工具无声地卡在那里。（**已交付**，A14；**推翻**了原文的「不强制关闭浏览层」）
14. As a Cafecli 用户, I want 浏览层里 ESC 只关闭浏览层, so that 我不会在想退出视图时误杀正在跑的回合。
14a. As a Cafecli 用户, I want 浏览层里按 Ctrl+C 照常停下正在跑的回合、而且不把我正在读的层关掉, so that 「停下来」不需要先退出阅读（ADR 0016 D3——ESC 是字节、被模态读走，Ctrl+C 是信号、不受影响）。
15. As a Cafecli 用户, I want 审批用 `y`/`n`/`a` + Enter 在输入框里回答, so that 我不用记住「审批时键盘规则不一样」。
16. As a Cafecli 用户, I want 审批待决时右提示符变成 `y / n / a`, so that 我不用读文档就知道怎么回答。
17. As a Cafecli 用户, I want 审批时直接按 Enter 会重新问我一次, so that 我误触回车不会被当成拒绝。
18. As a Cafecli 用户, I want 输入框在空闲时有完整的补全 / 历史 / 高亮, so that 现有的 `@path`、`@image:`、slash 命令体验一点不退。
19. As a Cafecli 用户, I want 输入框在回合运行时也有完整的补全 / 历史 / 高亮, so that 「任何时刻形态一致」不是只指长得像。
20. As a Cafecli 用户, I want 多行输入时输入框向下长、横线跟着走, so that 我贴长内容时版式不散。
21. As a Cafecli 用户, I want 在窄 / 矮终端上输入框自动减少装饰, so that 小窗口里活区不会被边框吃光。
22. As a Cafecli 用户, I want 提交过的输入在转写里显示为暗色底纹块、首行有 `›`、换行对齐正文, so that 我一眼能把自己说的话和模型说的话分开。
23. As a Cafecli 用户, I want 提交的输入在编辑区不留残影, so that 同一句话不会在屏幕上出现两次。
24. As a Cafecli 用户, I want 底部状态坞（模型 / ctx / token / cost / 耗时 / cwd）继续显示在输入框下方, so that 我不必为了常驻输入框牺牲运行态信息。
25. As a Cafecli 用户, I want 后台[[任务]]完成的 push 输出落在输入框上方而不打断我打字, so that 自治回合不会抢走我的光标。
26. As a Cafecli 用户, I want 终端 resize 时输入框与活区重新排版, so that 拖窗口不会留下错位的残留。
27. As a Cafecli 用户, I want 在不支持所需能力的终端上 Cafecli 依然可用（退回旧形态）, so that CI / 管道 / dumb 终端不会直接崩。
28. As a Cafecli 用户, I want 输入 `/plan` 得到「该模式已移除」的说明, so that 我不会以为是自己拼错了命令。
29. As a Cafecli 维护者, I want 输入框、活区尾、状态坞、光标位置能在无终端环境下断言, so that 交互回归不再只能靠手测。
30. As a Cafecli 维护者, I want 一条测试在 JLine 升级后 `Display.update` 不再是唯一写入路径时立刻变红, so that 这个架构假设不会悄悄失效。
31. As a Cafecli 维护者, I want `Main` 里不再有任何 `terminal.reader()` / `enterRawMode()`, so that 「终端按键只有一个消费者」是可 grep 的事实而不是口头约定。
32. As a Cafecli 维护者, I want 冻结路径只剩一条, so that 块间空行不再依赖 drain 时序。
33. As a Cafecli 维护者, I want 交互 REPL 从 `Main` 抽成无终端依赖的可驱动单元, so that 输入 regime 的行为可以像 `AgentSession` 一样被桩驱动断言。
34. As a Cafecli 维护者, I want 删除 `/plan` 与 `tui/` 后不再维护第二套交互模型, so that 之后每个交互决定只需验证一条路径。
35. As a Cafecli 用户, I want 浏览层里滚轮滚动在改造后依然可用, so that 我的浏览习惯不因输入框常驻而退化（`readLine` 重入频次上升会更频繁发出 mouse-disable，见实现决策中的时序陷阱）。

## Implementation Decisions

### 渲染与终端归属（ADR 0014）

- **`CaptureDisplay`**（新，`render/inline/`）继承 `org.jline.utils.Display`，覆写 `update(List, int, boolean)`，把行与光标位置交给渲染循环，不写终端。依据：JLine 4.0.0 中 `Display` 的全部终端写入都汇聚到该方法，`resize` / `reset` / `clear` 不写。
- **`CafeCliLineReader`**（新）继承 `LineReaderImpl`，覆写四处、**零行方法体拷贝**：
  - `redisplay(boolean)` — 重装 `CaptureDisplay` **并同步 `lastStatusSize`**，随后 `super.redisplay(flush)`，返回后断言 `display == capture`；
  - `printAbove(String)` / `printAbove(AttributedString)` — 改投[[冻结回卷]]队列；
  - `doCleanup(boolean)` — `super.doCleanup(false)`，免掉直写的 `println()`；
  - `clear-screen` widget 重绑。
- **`doDisplay()` 的第四个调用点必须处理**（Wave 0 spike 实测）：`redisplay:4202` 在 `super.redisplay()` **内部**、重装守卫之后重建 `display`，未处理时该次按键直接画进终端且零 capture 帧。同步继承来的 `protected int lastStatusSize` 使分支不可达；状态坞移出 `Status` 后 `getStatus(terminal,false)` 返回 null，该分支另按构造不可达（双保险）。
- **`LineReaderBuilder` 不可用**（final + 私有构造 + `build()` 硬编码 `new LineReaderImpl`）：`Main` 与 `RenderReproDriver` 两处建构点改为手工构造 + 逐项 setter 配置；**`variables` 只能走构造器**（`INPUT_RC_FILE_NAME` 构造期读取）。
- 覆写的骨架（来自 Wave 0 spike，散文无法精确表达的部分——重装、`lastStatusSize` 同步、`super` 后守卫三者的**顺序**是有意义的）：

  ```java
  @Override protected void redisplay(boolean flush) {
      installCapture();                                  // 覆盖 ctor / readLine:681 / WINCH:1280 的 doDisplay()
      Status st = Status.getStatus(getTerminal(), false); // 退役 Status 后恒为 null
      lastStatusSize = (st != null) ? st.size() : 0;      // 使 redisplay:4202 的 doDisplay() 分支不可达
      super.redisplay(flush);
      assertStillCaptured();                              // JLine 升级改了 doDisplay() 位置时立刻失败，而非静默双画
  }
  ```
- **输入框成为 pinned trailing `StripEntry`**：位于[[活区尾]]之后、状态坞之前，永不进入[[冻结回卷]]。`LiveRegionStrip` 的 `renderEntries` 从恒传 `cursorPos = -1` 改为把输入框的光标位置平移进合成帧坐标。
- **状态坞离开 JLine `Status`**：取**根本不创建**而非「创建后挂起」——`BottomStatusBar` 不再调 `Status.getStatus(terminal)`，JLine 自身只调非创建版，故 `Status` 恒为 null。理由（Wave 0 实测）：挂起只让 `redraw()` 成 no-op，`size()` 仍返回原行数继续占 `displayRows` 预算；且 `Status` 持有自己的 `MovingCursorDisplay` 直接写终端（每次 `update()` ~37 字符），是 `LineReader` 截获管不到的第二个画者。不创建则同时消掉这个画者与 `doDisplay:4202` 分支。状态坞改由渲染线程作为活区行画出；`reservedRows()` / `bindReservedRows` seam 消失，DECSTBM 滚动区不再使用，活区靠既有 clear→print→recreate 纪律自保。[[转写浏览层]]原先的 `statusBar.suspend()` / `restore()` 一并退役。
- **删除 `LiveRegionStrip` 的 `printAbove` 分支**：读者恒在读，该分支会吞掉整个活区。冻结统一走 `Display` 路径，`answersSeparated` / `isFullyPrintedWithContent` 与 `isFullyFrozen` 的重复一并删除。
- **降级路径**：终端不具备所需能力时不安装 `CafeCliLineReader`，使用原生 `LineReader` + 现有 plain 形态；`CAFECLI_RENDERER=plain` 仍是显式逃生口，不新增开关。

### 输入框形态

- 两条横线夹一行 `> `，横线暗色、按 `width - 1` 铺满；右提示符移入框内右对齐，随状态切换：空闲 `message / @path / @image` → 回合中 `steer · esc 停止` → 审批待决 `y / n / a`。
- **输入行带[[提交块]]的整行暗色底纹，`>` 用提交块的紫色**（ADR 0016 D6）。让它读起来像「框」而不是「两条分隔线」的是底纹，不是边框：空框也看得见（这才是「任何时刻都在」的可见证据），提交时同一片底纹原地上移成 `›` 块。**不做竖直边框**——输入行是行编辑器按终端宽度排好交过来的，加左右边框就得让它按一个不是终端宽度的宽度排版，那是 ADR 0014 六个耦合点之外的第七个。
- **捕获帧宽度与本帧宽度不符时，整个输入框合成零行**（ADR 0016 D4）：这是光标已经在守的规则（不符则回退 −1），行也照办。不只是观感——`inputLines.size()` 喂给活区尾预算，按旧宽度的行数算会让尾巴压过输入框与状态坞。
- 多行输入向下增长，横线跟随；超过上限后框内滚动。
- **短终端降级**：低于约 20 行只留上横线（2 行），低于约 12 行去掉横线只留 `> `。
- 编辑态标记 `* ` **退役**，编辑与提交都用 `>`；两者靠位置（框内 / 回卷）与暗色区分。
- **提交块**：全宽暗色底纹，首行 `›` 标记，续行缩进对齐正文，块内自行按终端宽度换行（wcwidth 感知，不依赖终端自动折行）。256 色 / 16 色分支复用 `DiffBlock` 已建立的能力探测。已知并接受：[[冻结回卷]]不重排，故窄化 resize 后旧提交块的底纹会出现参差半行。

### 输入语义

- **交互 REPL 从 `Main` 抽出**为无终端依赖的可驱动单元（与 ADR 0005 对 `AgentSession` 的处理同构），持有 readLine 循环、键绑定、提交路由（普通提交 / [[转向消息]] / [[审批应答]]）与[[取消键]]表。
- **提交路由**：有审批待决 → `pump.resolve`（空提交 → 重新呈现审批卡）；有回合在跑 → 转向消息；否则 → `AgentSession.postEvent(UserSubmit)`。
- **[[取消键]]表**（回合在跑者优先）：

  | | 回合运行中 | 空闲且框内有文字 | 空闲且框为空 | 模态开着 |
  |---|---|---|---|---|
  | ESC | 取消回合 + 附着任务、拒绝待决审批、丢弃未生效转向 | 无操作 | 无操作 | 只对模态说话 |
  | Ctrl+C | 同上，**框内文字保留** | 清空输入框 | 首次提示、~2 秒内再次按下退出 | 同左三列，**且不关模态** |
  | Ctrl+D | 无操作 | 无操作 | 退出 | 无操作 |

- **Ctrl+C 机制**：`readLine` 常驻即 JLine 的 `Signal.INT` handler 常驻，中断以 `UserInterruptException` 抛出并携带 `getPartialLine()`，据此把已键入文本 seed 回下一次 `readLine`。`readLine` 之间的空档另设**会话级 `Signal.INT` handler** 兜底——**覆盖面包括每个 raw-mode 模态**：`enterRawMode()` 不清 `ISIG`，而本仓库从未安装过任何 INT handler，所以今天在浏览层里按 Ctrl+C 会杀进程（ADR 0016 D3）。
- **ESC 的延迟不得退化**：作为 JLine 绑定，ESC 是 CSI 序列的前缀，键映射会等 `ambiguous-binding`（默认 1000ms）。设为 ≈100ms，对齐今天 `readInputBurst` 的窗口；**必须经构造器 `variables` 传入**（`defaultKeyMaps()` 在构造器里就读走它）。JLine 的歧义超时就是我们手写的那个分类器，所以分类器照删（ADR 0016 D2）。
- **删除**：`pumpWaitUntilIdle`、`readRuntimeControl`、`classifyRuntimeControl`、`decideEscCancel`、`handleRuntimeDisplayControl`、`RuntimeControl`、`clearAcceptedInput`。**注意 `handleRuntimeDisplayControl` 是回合中 Ctrl+O / Ctrl+Y 今天的唯一通路**，删它必须同时把这两个键接成常驻读取器上的 widget，否则静默回退两个已交付行为。

### [[转向消息]]

- 新增 `injectPendingUserSteering()`，作为 ReAct 迭代边界前置注入的第三个兄弟（与 LSP 诊断、PostToolUse hook context 同一通道），从并发槽 drain，形态与 `flushPendingHookContext()` 一致。
- **回合结束兜底**：回合在下一个迭代边界前结束时，未 drain 的转向消息重新投递为 `UserSubmit`，不得静默丢失。
- 提交瞬间即写入转写为 `›` 块并进入[[保留窗口]]。回显必须是**源形态**的 `SubmittedPromptItem`（否则窄化重画留半行底纹），入队与 `ReplayLog.append` 成对走那条唯一的有序队列（A8b）。
- **[[保留窗口]]里的位置改记「一段源」而不是「一个条目」**（ADR 0016 D5）：位置今天钉在条目**第一次**提交的那一刻，而流式答案在首行落回卷时就进了日志，于是回合中途提交的转向消息排在那个答案的**全部**输出之后。这在[[转写浏览层]]里**无条件复现**（层内渲染的是答案的全部样式行），不需要缩放——而浏览层恰恰是用户去问「我那句纠正落在哪儿」的地方。本 PRD 的整个卖点就在位置上，所以这条在 issue 10 内解决，不挂账。
- 已知并接受：快照锚的是回合原始输入，被转向过的回合与该标签不再完全对应（ADR 0005 已接受的 effort 级回退再放宽一档）。

### [[审批应答]]

- `InlineApprovalPrompter` **退役**（其 `enterRawMode` + `reader().read()` 与常驻 `readLine` 抢同一个 `NonBlockingReader`）。`ApprovalPump` 的 park/resolve 契约、`callerContext` 归属、会话级「全部批准」范围不变。
- `ApprovalBlock` 继续由渲染线程绘制/更新/移除，**任何渲染线程上的终端读取都是死锁 bug** 这条纪律不变。

### [[转写浏览层]]

**本节已按已交付形态重写。** 原文的「渲染线程快照 + 挂起协议 + 审批不关层」全部作废——那三条已由
destructive-resize-epoch 用另一条路取代或推翻，见下。

- Ctrl+O 在空闲与回合运行中都可进入（A13，已交付）。浏览层拿到的是[[保留窗口]]的**活视图**而不是快照，每 50ms 轮询重读，因此**实时跟随**；视口停在末尾时跟进，向上翻后不被推走，回到末尾即恢复跟随。这条通得了，是因为提交发生在[[geometry turbulence]]闸之上：[[模态租借]]期间渲染循环一个字节不写，源模型照常增长。
- **[[模态租借]]取代了挂起协议**（A12，已交付）：租借在**帧边界**上交接，期内每一帧写零字节，几何量照常观察、[[destructive resize reset]] 推迟到归还时**恰好付一次**。浏览层自持缩放（它占着终端，没有别人能替它修屏）。
- **审批到达会关掉浏览层**（A14，已交付，**推翻原文**）：浏览层没有[[输入框]]，而审批经输入框应答、输入框在主屏；请求方 park 在一个**没有超时**的 get 上，让它干等更糟。信号是计数不是标志位。
- **原 issue 唯一没被这条路回答的是它的安全论证**：浏览层在输入线程上渲染渲染线程正在改的块。改由 ADR 0016 D1 承担——条目只暴露不可变源，排版与缓存归视图，竞态按构造不可达且无锁。归 issue 11。
- 浏览层内 ESC / `q` / Ctrl+O 关闭；层内 ESC **只关层**，不是[[取消键]]。**Ctrl+C 例外**：它是信号、不经字节流，因此照常取消回合，**且不关层**（ADR 0016 D3）。Ctrl+T 全局折叠/展开思考（ADR 0013 不变）。
- **鼠标追踪的时序陷阱**（Wave 0 实测）：`LineReaderImpl.doCleanup` 每次都无条件发 49 字符的 mouse-disable（`MouseSupport.trackMouse`），即便 `Option.MOUSE` 关闭，且**无任何 override 钩子**可拦（只能靠摘掉终端的 `key_mouse` 能力）。改造后 `readLine` 在每次提交后重入，故该序列的发射频次显著高于现在；而浏览层是唯一主动**开启**鼠标追踪的地方。二者当前严格串行、无冲突，但浏览层进入 / 退出若与某个 `readLine` 边界交错，会静默关掉浏览层的滚轮滚动。实现时须保证浏览层的鼠标追踪开启发生在 `readLine` 让出之后、且退出时自行恢复，并把「滚轮在浏览层中可用」列为手测项（S-E）。

### 删除（ADR 0015）

- `PlanExecuteAgent`、`plan/`（`ExecutionPlan` / `Planner` / `Task`）、`PlanReviewInputParser`、`CliCommandParser.SWITCH_PLAN`、`prompts/modes/plan.md` 与 `planner.md`、`PromptMode.PLAN` / `PLANNER` 及 5 个测试文件。
- `tui/` 整包、`Main` 的 `TuiBootstrap` 分支、`phase16-smoke` profile 中的 `TuiBootstrapTest`；`RendererFactory.Mode.LANTERNA` 收敛。
- 随之失去最后调用点的按键助手：`readSingleKeyFromTerminal`、`readPrefillInputFromTerminal`、`readEscapeInput`、`readInputBurst`、`classifyEscapeSequence`、`EscapeSequenceType`、`KeyReadResult`、`PrefillResult`；`PromptInput` 收敛为纯字符串。
- `/plan` 保留墓碑输出（非「未知命令」）。

### 交付顺序

- **Wave 0（spike）—— 已完成，GO-WITH-CAVEATS**。产物已落入 `com.cafecli.spike`（`CaptureDisplay` / `SpikeLineReader` / `TracingTerminal` / `TracingWriter` / `JLineInterceptionSpikeTest`），在主工作树上验证通过，直接充当 S-C 常驻守卫；Wave 2 把 `CaptureDisplay` / `SpikeLineReader` 提升为生产类时，测试改指生产类、`spike` 包退役。
- **Wave 1（删除）**：ADR 0015 全部删除面 + 文档同步。
- **Wave 2（seam）**：`CaptureDisplay` / `CafeCliLineReader`（含 `lastStatusSize` 同步 + `super` 后守卫断言 + 手工构造替代 `LineReaderBuilder`）/ 输入框 StripEntry / 单冻结路径 / 状态坞入活区（不再创建 `Status`）/ REPL 抽出 / 取消键表 / 提交块样式 / 降级路径。
- **Wave 3（行为）**：转向消息（含日志位置改为源区间）、审批经输入框、取消键表（含模态列）、输入框装饰。
- **Wave 1–2 已交付**（issues 01–06，commits `248fff8`…`56e9b0d`）。「回合中 Ctrl+O」已由 destructive-resize-epoch 交付（A12/A13/A14），**不在 Wave 3 内**；它留下的安全论证成为 issue 11（视图拥有渲染缓存），**不被任何 issue 阻塞**——那条竞态现在就活在 `main` 上。

## Testing Decisions

好的测试只断言**外部可观察行为**：合成帧字符串、提交给[[冻结回卷]]的字节、`AgentSession` 上产生的事件、对话历史里注入的消息。不断言私有字段、不断言帧数、不断言内部调用次序。优先复用既有 seam，新 seam 只加两个。

- **S-A（既有，首选）—— 字节捕获 mock `Terminal` + 真实 `InlineRenderer` + `ManualFramePump` / `ManualFrameTicker`。** 先例：`RenderFixupReproTest`、`InlineRendererTest`、`LiveRegionStripTest`、`DiffBlockTest`。覆盖：输入框在合成帧中的位置与内容、光标位置平移、单冻结路径的块间空行、状态坞作为活区行、短终端横线降级、提交块底纹 / `›` / 续行对齐 / 自换行、resize 重排。
- **S-B（新，最高 seam）—— 交互 REPL 抽出后的可驱动单元。** 先例与依据：ADR 0005 对 `AgentSession` 做过同一件事（「须从 `Main` 抽出为无终端依赖的可驱动单元，否则不可测」）。测试用建在管道流上的 JLine terminal 写入字节、断言合成帧与产生的事件。覆盖：回合中键入不被吞、Enter → 转向消息、取消键表全部格子、Ctrl+C 保留 partial line、审批应答路由、空提交重新询问、右提示符随状态切换。
- **S-C（新，窄而永久）—— `CaptureDisplay` 截获守卫。** Wave 0 spike 已实现并验证（`TracingTerminal` 继承 `ExternalTerminal` 包装 `writer()`——必须继承而非代理，因为 `Status.getStatus` 要求 `instanceof AbstractTerminal`；`TracingWriter` 记录每个字符 + 完整栈），直接升级成常驻回归。断言不是「零字节」而是三条：(a) 全栈扫描下 `Display.update` 贡献的写入为 **0**；(b) `super.redisplay()` 返回后 `display == capture`（`doDisplay:4202` 守卫）；(c) 残留写入集合仍等于已枚举的模式位清单——集合变大即 JLine 行为变了。覆盖 ADR 0014 列出的**全部六个**版本耦合点，而非只覆盖 `Display.update` 一条。驱动内容：打字、退格、Ctrl-U、Tab 补全菜单、多行缓冲、非空右提示符、编辑中 WINCH、accept-line。
- **S-D（既有）—— 桩 `LlmClient` 事件循环 seam。** 先例：`AgentSessionTest`、`AgentLspDiagnosticsTest`、`AgentFinishGateTest`。覆盖：转向消息在迭代边界注入且在下一次 LLM 调用前出现在对话历史；回合先结束时未生效转向退化为 `UserSubmit`；取消只波及前台回合与[[附着]]任务。
- **S-E（既有，手测）—— `RenderReproDriver`** 扩展常驻输入框 + 长回合。覆盖 mock terminal 证明无法复现的滚动区相关症状（该 harness 自身文档已声明这一边界）。**不进 CI**；状态坞迁移风险须在 Terminal.app 上专门手验（该终端无同步输出，撕裂最先显形）。
- **可 grep 的验收**：改完后 `Main.java` 中 `terminal.reader()` 与 `enterRawMode()` 出现零次，接入 CI。
- **刻意不写的测试**：浏览层竞态不用并发测试兜底，而是让它按构造不可达——条目只暴露不可变源，排版与缓存归视图（ADR 0016 D1）。断言落在**结构**上（`StripEntry` 接口不再有可变缓存方法；给某个实现加上"提交后仍可变"的状态即变红，与当时谁在读无关），渲染仍由 `TranscriptOverlayModelTest` / `OverlayDocumentTest` 覆盖。原文写的「快照 API 只返回 `List<AttributedString>`」已作废：A13 交付了实时跟随，快照形态不再存在。
- 回归门：`mvn test -Pquick`（常规）、`mvn test -Pphase16-smoke`（TUI，需摘除 `TuiBootstrapTest`）。

## Out of Scope

- **Claude Code 式 plan mode（权限模式形态）**：本 PRD 只删除 DAG 执行器实现。若日后要做该形态对齐，落点是 ReAct 之上的 `ApprovalPolicy` / [[生命周期 Hook]]，与这里删掉的实现无关。
- **单键审批**（不按 Enter 直接 `y`/`n`）：需要在审批待决时压入临时 keymap，属输入框之上的增量，评估后再决定是否做。
- ~~**浏览层实时跟随（tail -f）**：已否决~~ —— **已交付**（ADR 0010 修订 A13）。当初否决它的两个前提
  （「每帧重渲整个保留窗口」「输入线程与渲染线程共享块级锁」）分别被视口级排版与 ADR 0016 D1 消解。
- **流式 LLM 回答的中途打断**：取消仍是协作式的、落在回合边界；`GLMClient.chat` 无 mid-stream 取消，本 PRD 不改。
- **任务级外科回退**：快照仍锚串行主回合边界，`revert_turn` 仍是 effort 级。
- **[[游离]]任务的取消**：仍只经 `/task cancel`，取消键不触达。
- **无人值守路径**（`run --file` / `serve --http`）：不引入输入框、事件泵或浏览层，行为不变。
- **JLine 升级**：本 PRD 钉在 4.0.0，不顺带升级。

## Further Notes

- **多生产者单写者不变式的净效果是「范围扩大、约束不变」**：今天「单写者」只在 regime 内成立，两个 regime 本身就是两个轮流上台的写者；改后输入行从写者降级为生产者，[[活区]]从 `[尾]` 扩为 `[尾][输入框][状态坞]`，全部由渲染线程合成一帧。这是本次改动对架构的核心影响，也是它值得做的理由。
- **ADR 0010 对 approach B 的定价在此被修正**：它估「覆写 `redisplay` = 抄 ~200 行方法体」，前提是要改 `redisplay` 算什么；实际只需换它写到哪。
- **Wave 0 已完成，结论 GO-WITH-CAVEATS**：核心假设（`Display.update` 是 `Display` 唯一写入路径）静态与实测双向成立——一次完整 `readLine` 共 89 字符写入，`Display.update` 贡献 0。代价是版本耦合面比原估计大：**六个**耦合点而非一个，由 S-C 整体钉住。无头非 dumb 终端可构造，S-B / S-C 的测试策略成立。
- **本次改动把最不可测的部分变成最可测的部分**：`Main` 里现存「⚠️ 终端侧逻辑，仅手测」的注释，正是这批 bug 的产地；改后输入框以 `lines + cursorPos` 汇入 `LiveRegionStrip.renderFrame()`，而它本就返回整帧字符串。
- **最高风险项是 Wave 2 的状态坞迁移**：它移除了目前保护底部行的 DECSTBM 滚动区，改由活区的 clear→print→recreate 纪律承担。
- 文档同步义务（AGENTS.md 硬规则 #1 / #2）：AGENTS.md 的双执行路径表、硬规则 #3（Plan 审阅交互）、验证路径表的 `ExecutionPlanTest` 行、inline 渲染约定中关于 `* ` / `>` / `reservedRows` / `printAbove` 的条目；README；ROADMAP 状态。
- CONTEXT.md 已随本轮讨论更新：新增[[活区]] / [[输入框]] / [[转向消息]] / [[审批应答]] / [[取消键]]，修订[[活区尾]] / [[转写浏览层]] / [[附着]]·[[游离]]。
