# 05 — `CaptureDisplay` + `CafeCliLineReader`：输入行进活区，冻结路径收敛为一条

Status: ready-for-human
Type: HITL（需真实终端人工验收）

## Parent

`.scratch/always-live-input-box/PRD.md`（ADR 0014 决策 1/2/5）

## What to build

让 JLine 行编辑器**交出画笔**：它继续负责编辑语义、补全、历史、高亮，但不再自己写终端，而是把「要显示的行 + 光标位置」交给渲染循环，由渲染线程合成进同一帧。这是整个改造的架构支点——输入行由此从「第二个写者」降级为「又一个生产者」。

三件事必须同时落地，因为它们互相咬合、拆开会留下坏中间态：

1. **画笔交接**：把 Wave 0 spike 里已验证的截获 `Display` 与 `LineReader` 子类提升为生产类。截获 `Display` 只覆写三参 `update`，捕获行与光标位置、不写终端（另两个 `update` 重载与 `updateAnsi` 都委派到它；`clear` / `reset` 只改字段）。
2. **输入行进活区**：输入行成为活区末尾的**常驻条目**（位于活区尾之后、状态坞之前），永不进入[[冻结回卷]]；活区的合成帧必须携带**真实光标位置**（现状恒传 −1），否则光标消失、不可用。
3. **冻结路径收敛**：删除活区里「行编辑器在读 → 走 `printAbove` 批量追加」的那条分支。子类覆写了 `printAbove`（改投冻结队列），若保留原分支会自我递归；且该分支不重绘，会吞掉整个活区。收敛后 `answersSeparated` / 「答案是否已全部打印」与「答案是否已全部冻结」的重复逻辑一并删除。

本 issue 的输入行**保持朴素**（`> ` + 文本，无横线装饰）——装饰属于 09，此处只证明通路。

### 三个 Wave 0 实测出来的坑（必须处理）

- **`doDisplay()` 有第四个调用点**，在行编辑器重绘方法**内部**、重装守卫之后触发（JLine `Status` 尺寸变化时），会把截获 `Display` 换掉。实测未处理时该次按键的提示符与缓冲直接画进终端且零捕获帧。issue 04 之后 `Status` 恒为 null 使该分支按构造不可达，但仍须同步继承来的 `lastStatusSize` 作纵深防御。顺序有意义（来自 spike）：

  ```java
  @Override protected void redisplay(boolean flush) {
      installCapture();                                   // 覆盖构造 / readLine 起始 / WINCH 三处 doDisplay()
      Status st = Status.getStatus(getTerminal(), false); // 退役 Status 后恒为 null
      lastStatusSize = (st != null) ? st.size() : 0;      // 使内部那次 doDisplay() 分支不可达
      super.redisplay(flush);
      assertStillCaptured();                              // JLine 升级改了调用位置时立刻失败，而非静默双画
  }
  ```

- **JLine 的 `LineReaderBuilder` 不可用**（final + 私有构造 + `build()` 硬编码基类）。交互 CLI 入口与渲染复现驱动两处建构点都改为手工构造子类 + 逐项 setter 配置。注意 **variables 只能走构造器**（输入配置文件名在构造期即被读取）。
- **降级路径**：终端不具备所需能力时不安装子类，使用原生行编辑器 + 现有形态，行为与今天一致。

## Acceptance criteria

- [x] 空闲提示符下打字、退格、Ctrl-U、光标移动、多行缓冲、粘贴均正常 —— `CaptureDisplayInterceptionGuardTest` 无头驱动打字 / 退格 / Ctrl-U / Ctrl-B 光标移动 / 未闭合引号的多行缓冲，逐项断言捕获帧内容；粘贴走 `BRACKETED_PASTE`，与普通自插入同一条 `redisplay` 通路
- [x] 补全（Tab 候选菜单）、历史召回、实时高亮、右提示符全部与改造前一致 —— 同上测试：Tab 候选菜单、上箭头历史召回、非空右提示符 `[RIGHT]` 都出现在捕获帧里；高亮走 `Highlighter`，与菜单 / 次级提示符同为 `super.redisplay` 的私有助手产物，零行方法体拷贝故行为不变
- [x] 输入行出现在合成帧中活区尾之后、状态坞之前，且**永不**进入冻结回卷 —— `LiveRegionStripTest.inputBoxRowsSitAfterTheLiveTailAndBeforeTheDock` / `inputBoxNeverEntersFrozenScrollback`、`InputBoxLiveRegionTest.typedTextAppearsInTheComposedFrameAboveTheStatusDock`
- [x] 合成帧携带正确光标位置：光标停在输入文本中的实际编辑位置，多行与含中日韩字符时仍正确 —— `LiveRegionStripTest.composedFrameCarriesTheInputCursorTranslatedIntoFrameCoordinates` / `multiRowInputCursorKeepsItsRowAndColumnAfterTranslation` / `anAbsentInputBoxLeavesTheFrameCursorUnpositioned`（平移按 JLine 的 `row * (columns + 1) + col` 编码，列宽由 JLine 侧 wcwidth 算好，此处只做行偏移）
- [x] 全栈扫描下，一次完整 `readLine` 期间由 `Display.update` 产生的终端写入为 **0** —— `CaptureDisplayInterceptionGuardTest.aFullReadLineDriveProducesZeroDisplayWritesAndKeepsTheCaptureInstalled`（`TracingWriter` 记录每个字符 + 完整栈）
- [x] 行编辑器重绘方法返回后仍持有截获 `Display`（守卫断言生效）—— 同上测试断言 `captureSwapsAfterRedisplay() == 0` 且 `display == capture`
- [x] 活区不再有 `printAbove` 分支；块间空行在 答案→工具 / 答案→diff / 工具→思考 各边界均为恰好一行 —— `LiveRegionStrip` 里该分支已删除（连同 `answersSeparated` / `isFullyPrintedWithContent` / `fullyPrintedAndFinished` / `StripEntry.drainPrintAbove`）；`RenderFixupReproTest.anAnswerToDiffBoundaryHasExactlyOneBlank` / `everyBlockBoundaryInAnIterationHasExactlyOneBlankRegardlessOfType` / `theSingleFreezePathHoldsWhileTheLineEditorIsReading`。顺带关掉 issue 04 留的缺口：`theDockIsRepaintedWhileTheLineEditorIsReading`——空闲等输入时的后台 push 不再把状态坞顶进回卷
- [x] 覆写的 `printAbove` 把内容投进冻结队列而非直写终端；启动首屏与 Ctrl+V 抓图提示等既有调用点仍正常显示 —— `CaptureDisplayInterceptionGuardTest.printAboveIsRoutedToTheFreezeSinkInsteadOfTheTerminal`、`InputBoxLiveRegionTest.printAboveFromTheLineEditorBecomesFrozenScrollbackNotADirectWrite`（调用点不变，落点变了）
- [x] 提交后编辑区不留残影，提交内容以转写块写回（不再需要手工清除编辑行的逻辑）—— `doCleanup` 覆写为 `super.doCleanup(false)` + 投一帧空的；`CaptureDisplayInterceptionGuardTest.acceptingALineClearsTheInputFrameAndPrintsNoNewline`、`InputBoxLiveRegionTest.acceptedInputLeavesNoResidueInTheLiveRegion`。`InlineRenderer.clearAcceptedInput` 在交出画笔时短路成 no-op（仅降级路径仍保留其原行为）
- [x] 终端 resize 后输入行与活区重新排版，无错位残留 —— `InputBoxLiveRegionTest.aResizeRelaysOutTheInputLineAndTheLiveRegionTogether`（WINCH 后捕获帧宽度跟到新值、光标按新 stride、状态坞同帧重排）
- [x] 能力不足的终端走降级路径，不安装子类，行为与今天一致；`CAFECLI_RENDERER=plain` 仍可用 —— `CafeCliLineReader.supported(...)` 与 `InputBoxLiveRegionTest.terminalCapabilityGateDecidesWhetherTheBrushIsHandedOver` / `aStockLineEditorIsNotWiredAsAnInputBoxProducer`；`Main` 只在 `renderer instanceof InlineRenderer && supported(terminal)` 时安装子类
- [x] Wave 0 spike 测试改指生产类并通过，`com.cafecli.spike` 包退役 —— spike 升级为 `CaptureDisplayInterceptionGuardTest`（+ `TracingTerminal` / `TracingWriter` 迁入 `com.cafecli.render.inline` 测试包），断言化为三条 S-C 守卫并静态钉住 ADR 0014 的**全部六个**版本耦合点；`src/test/java/com/cafecli/spike/` 已删除
- [x] `mvn test -Pquick` 与 `mvn test -Pphase16-smoke` 全绿 —— `-Pphase16-smoke` 301/301 绿；`-Pquick` 989 个用例仅剩已知的顺序相关 flake `TerminalMarkdownRendererTest.wrapsWideMultiColumnTableInsideTerminalWidth`（单独跑绿，与本改动无关）。`mvn clean package` 亦绿
- [ ] **人工验收**：真实终端下输入体验与今天无可感差异；流式输出期间底部不撕裂 —— **未做，无头环境按定义无法验收**，待用户在真实终端（Terminal.app 优先，无同步输出，撕裂最先显形）确认
- [x] 文档同步：AGENTS.md 中关于 `printAbove` 作为冻结边界、活区重绘归属的条目 —— 已改写 `printAbove` 条目（改为「入口而非写者」+ 活区无 `printAbove` 分支）、活区重绘归属条目（新增「活区 = 尾 + 输入行 + 状态坞」与光标平移）、空行归属条目（单一冻结路径 + `claimTrailingSeparator`）、左提示符 `* ` → `> `、首屏 `printAbove` 落点、以及新增的「行编辑器交出画笔」条目

## 本 issue 刻意未做

- 输入框装饰（两条横线、右提示符移入框内、短终端降级）—— 属 issue 09，本 issue 的输入行保持 `> ` + 文本
- 常驻 `readLine`（回合运行中也有输入框）、转向消息、审批经输入框、回合中 Ctrl+O —— 属 Wave 3；本 issue 只把输入行在 `readLine` 期间接进活区，证明通路
- 转写浏览层的挂起协议（浏览期间渲染循环停写、恢复时先刷冻结）—— 属 Wave 3。现状：浏览层仍是空闲态才可进入，期间若有后台 push 到达，渲染线程会写进 alt screen（该风险在改造前经 `printAbove` 也存在，非本 issue 新引入）

  > **本条已过时（2026-08-26）**：挂起协议由[[模态租借]]取代（ADR 0010 修订 A12），浏览层已改为**任何时刻可进入**且实时跟随（A13）。此处描述的 alt-screen 写入风险随之消失。它留下的另一半——输入线程在浏览层里渲染渲染线程正在改的块——见 ADR 0016 D1 / issue 11。

## Blocked by

- `.scratch/always-live-input-box/issues/04-dock-leaves-jline-status.md`
