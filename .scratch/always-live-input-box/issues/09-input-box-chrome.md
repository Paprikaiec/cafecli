# 09 — 输入框装饰：两条横线、多行增长、短终端降级

Status: ready-for-agent
Type: HITL（需真实终端视觉验收）

## Parent

`.scratch/always-live-input-box/PRD.md`

## What to build

给 05 里跑通的朴素输入行加上最终形态。

```
──────────────────────────────────────────────────
> 重构这个类，先看一下现有测试_        steer · esc 停止
──────────────────────────────────────────────────
 react · 42% ctx · in 12.3k/out 1.1k · $0.04 · 1m12s
```

- **两条横线夹一行 `> `**，暗色，按 `width - 1` 铺满——与状态坞同一条规则（活区里凡是被我们补齐到整行的东西都少占一列，光标就不会落在最后一列）。
- **输入行带[[提交块]]的整行暗色底纹**（ADR 0016 D6），同样补齐到 `width - 1`，色彩分支复用 `DiffBlock` / `SubmittedPromptBlock` 已建立的 256 色 / 16 色探测。**这才是让它读起来像「框」而不是「两条分隔线」的东西**：空框也看得见（「任何时刻都在」的可见证据就是这个），而提交时同一片底纹原地上移成 `›` 块，读起来像这个框把内容冻上去。
- **`>` 标记用提交块的紫色**（256 色 141，16 色退化 magenta），让活区里的标记与回卷里的 `›` 是同一族。
- **不做竖直边框**（明确排除，别"顺手加上"）：输入行是行编辑器按**终端宽度**排好经 `CaptureDisplay` 交过来的，给它加左右边框就得让行编辑器按一个不是终端宽度的宽度排版——那是 ADR 0014 六个耦合点之外的第七个。已评估并否决的两个替代：横线加圆角 `╭╮╰╯`（依赖字体有字，缺字退化成方框，而底纹已经承担了同一个信号）；经提示符做左竖线 `│ > `（真的免费，JLine 排版时会算进提示符宽度，但它永久吃掉两列正文来说底纹白说的话）。
- **右提示符移入框内右对齐**，随状态切换（该切换在 07 已接线，此处只负责它在框内的排版）。注意右提示符是 JLine 自己贴到终端右缘的，不需要我们排——**不要**对捕获帧的行做二次裁剪或补齐。
- **多行输入向下增长**，横线跟随；超过上限后框内滚动，横线保持在首尾。
- **`* ` 编辑态标记退役**：编辑与提交都用 `>`。二者靠位置（框内 vs 回卷）与暗色区分——03 已让提交块带底纹与暗色 `›`，因此不会混淆。
- **短终端降级**（关键，别跳过）：输入框 3 行 + 状态坞 2–3 行意味着**常驻占掉 5–6 行**；80×24 终端上是四分之一屏，代价由活区尾承担。低于约 20 行只留上横线（2 行），低于约 12 行去掉横线只留 `> `。同样的内容、更少的装饰，让「任何时刻都在」的承诺在小终端上仍然成立。

  > **20 / 12 这两个数是拍的，不是量的**（本轮 grill 记下的未决项）。判据应该是「活区尾还剩几行才算有用」——它同时被状态坞、输入框与[[bounded replay window]]挤压。落地时要么在真机上按 80×24 / 80×20 / 80×12 三档验一遍、把数改成量出来的，要么在 issue 里明说它们是拍的。**不要**默默让它们看起来像实测值——这个仓库为混淆这两者付过代价。

## Acceptance criteria

- [x] 正常尺寸终端下输入框呈现为「上横线 / `> ` 输入行 / 下横线」，横线按 `width - 1` 铺满且为暗色
      —— `InputBoxChromeTest.twoDimRulesBracketTheInputRowAndSpanWidthMinusOne`。
      **修订（人工验收第一轮）**：`width - 1` 只是**下界**。行编辑器把一行排满时它就是 `width` 列，
      横线随之长到 `width`，否则右端露出一块底纹（缺陷 1）。见 `InputBoxChrome.boxWidth`。
- [x] **手动换行**：Shift+Enter / Option+Enter 在框里插一个换行而不是提交；Enter 仍然提交
      —— `InteractiveReplTest.shiftEnterAndOptionEnterInsertANewlineInsteadOfSubmitting` + 对照组
      `plainEnterStillSubmits`。**Terminal.app 默认那一档修不了**（Shift+Enter 与 Enter 逐字节相同）。
- [x] **退格穿过多行后屏上不留旧行**
      —— `InputBoxLiveRegionTest.backspacingAcrossWrappedCjkRowsLeavesNothingBehind`（读屏，不读字节）
- [x] 输入行带整行暗色底纹，补齐到 `width - 1`；**框为空时底纹仍可见**（这是本 issue 的主判据）
      —— `anEmptyBufferStillCarriesTheFullWidthWash`（断言第 0 列与第 58 列都带 `48;5;236`）；
      `theWashIsTheSameOneTheSubmittedBlockUses` 钉住它与[[提交块]]是**同一片**
- [x] 底纹在 256 色 / 16 色 / `NO_COLOR` 三条分支下分别正确，且 ANSI 关闭时无转义残留
      —— `aSixteenColourTerminalGetsNoBackgroundWashAndIsNotPadded` /
      `ansiDisabledLeavesNoEscapeResidueAnywhereInTheBox`（逐字符扫 ESC=27）/
      `theTerminalsOwnCapabilitiesSelectThePalette`
- [x] `>` 标记为提交块的紫色，与回卷里同一条提交的 `›` 视觉同族
      —— `theLiveMarkerIsTheSamePurpleAsItsFrozenCounterpart` / `theWashKeepsTheMarkersOwnPurpleForeground`
      （底纹压在每个字符自己的样式**之下**，所以标记的紫色活着）/ `theMarkerDegradesToMagentaAndThenToPlainText`
- [x] 输入框**不含**任何竖直边框字符；行编辑器始终按真实终端宽度排版（可 grep：没有任何地方向它报告非终端宽度）
      —— `thereAreNoVerticalBorderCharactersAnywhere` / `aCapturedRowIsNeverReclippedOrRewrapped`；
      grep 复核：`CafeCliLineReader` 只取 `getTerminal().getBufferSize()`，全仓库无第二个宽度来源
- [x] 捕获帧宽度与本帧宽度不符时，横线与底纹**随输入行一起不画**——不得只画横线留一个空壳（D4 的规则本体在 07 落地，本 issue 只负责让新增的装饰服从它）
      —— `nothingToComposeMeansNoRulesAndNoWashEither` / `LiveRegionStripTest.theRulesAreNotPaintedWhenTheInputRowsAreNot`
- [ ] 右提示符在框内右对齐；输入文本变长逼近右提示符时不重叠、不错位
      > **只落了一半。** 机制这一半有判据：右提示符是 JLine 自己贴到终端右缘的，我们**不碰**它——
      > `aCapturedRowIsNeverReclippedOrRewrapped` 钉住「不做二次裁剪 / 补齐」。但**右对齐的观感与逼近时
      > 不重叠**是 JLine 的排版结果，mock 帧里看不出来，归人工验收（见下）。
- [x] 多行输入时框向下增长、横线跟随；超过上限后框内滚动且首尾横线保持
      —— `multiLineInputGrowsDownwardWithTheRulesFollowing` / `pastTheCapTheBoxScrollsInternallyAndKeepsBothRules` /
      `scrollingKeepsTheCursorRowVisibleWhenItIsNotAtTheEnd` / `theInputRowsWinTheLastRowWhenTheBoxCannotAffordItsOwnRules`
      （放不下时**先扔装饰、后扔内容**）
- [x] 终端行数低于约 20 时只留上横线；低于约 12 时无横线只留 `> `；两种降级下输入仍完全可用
      —— `belowTheTwoRuleThresholdOnlyTheTopRuleSurvives` / `belowTheOneRuleThresholdOnlyTheInputRowSurvivesAndIsStillWashed` /
      `theLadderRungsAreExactlyTwentyAndTwelve` / `LiveRegionStripTest.aShortTerminalDropsTheRulesButKeepsTheInputRow`。
      **注意：20 / 12 仍然是拍的，不是量的**（见下文「仍未落地」）
- [ ] 终端宽度变化时横线与右提示符重新排版正确
      > 横线随宽度重排是纯函数、每帧按本帧宽度产出，但**没有一条用例专门在缩放序列里断言它**；
      > 现有缩放判据（`ScreenTruthRegressionTest`）覆盖的是活区整体，不是横线本身。未验，留空。
- [x] `* ` 标记不再出现在任何路径；编辑态与提交态都用 `>` 且可区分
      —— grep 复核：`src/main/java/` 中 `"* "` 作为提示符零出现
- [x] 含中日韩字符时横线长度与右提示符位置按列宽计算正确（横线与底纹这一半）
      —— `cjkIsPaddedAndMeasuredByColumnWidthNotCharacterCount`。**右提示符位置那一半同上，归人工验收**
- [x] 无头帧断言覆盖：三种终端高度下的框形态、多行增长（**右提示符位置除外**，见上）
      —— `InputBoxChromeTest` 22 条 + `LiveRegionStripTest` 4 条
- [ ] `mvn test -Pquick` 与 `mvn test -Pphase16-smoke` 全绿
      > `-Pphase16-smoke` **450/450 全绿**。`-Pquick` 1230 条里 1 条失败，仍是
      > `TerminalMarkdownRendererTest.wrapsWideMultiColumnTableInsideTerminalWidth`。
      > **本轮把这条 flake 的成因查清了**：`AnsiStyle.ENABLED` 是 `static final`、每 JVM 只算一次的
      > `paicli.render.color` 快照，而 `TerminalMarkdownRendererTest` 在自己的静态初始化里把该属性设为
      > `false`——于是「哪个测试类先加载 `AnsiStyle`」决定整个 JVM 的色彩档位，它自己抢输那一次就红。
      > 本 issue 有两条新断言被同一机理反向咬到，已改为断言 `Palette.fromCapabilities`（不含全局开关）。
      > **根因已确诊、未修**；修它才是让 `-Pquick` 真正全绿的那一步。
- [ ] **`RenderReproDriver` 扩展常驻输入框 + 长回合**（PRD 的 S-E 手测通路，此前不属于任何 issue，现归本 issue）。人工验收与 D4 / D6 的真机确认都要经它；mock terminal 证明不了滚动区相关症状，这是那条通路唯一的入口
      > **未做。** 它本轮只拿到 issue 07 的 `ambiguous-binding` 修正（7 行），没有为常驻输入框 + 长回合扩展。
- [ ] **人工验收**：真实终端下横线不断裂、不闪烁；连续打字与快速换行时框不抖动；底纹在 CJK 与 emoji 尾字处补齐正确、不留缺口
      > **第一轮已跑（2026-08-28），报了三个缺陷，全部已修**（底纹比横线宽一列 / Shift+Enter 不换行 /
      > 退格穿过多行删除不完全，见下文「人工验收第一轮」）。三条都先离线复现、修完再确认修复前变红。
      > **仍未完成**：清单 8 项里 1 / 3 / 5 / 7 / 8 未跑，2 / 4 / 6 需在修复后复验。
- [x] 文档同步：AGENTS.md 中「编辑提示符 `* ` / 提交提示符 `>`」的条目改写
      —— AGENTS.md 该条重写 + 新增输入框装饰条目与色彩档位条目；ADR 0016 D6 补 Delivered 记录

## 人工验收第一轮（2026-08-28，用户在真实终端上跑）

报了三个缺陷，**全部已修，且三条都先在离线复现、修完再确认修复前变红**。

### 缺陷 1 — 底纹比横线宽一列，右端突出一块

- **现象**：长输入把行填满时，暗色底纹一直铺到终端右缘，两条横线却在前一列停住。
- **成因**：横线按 `width - 1` 画（与状态坞同一条规则），而输入行**不是我们排的**——
  `LineReaderImpl` 按**终端整宽**折行，所以一行填满时它就是 `width` 列。离线量得：横线 39 列、
  满行 40 列。
- **修复**：`InputBoxChrome.boxWidth(...)`——框宽取**本框可见行**的最大列宽，下界 `width - 1`、
  上界 `width`。空框仍是 `width - 1`（最后一列照旧让出去），满行那一帧横线跟着长到 `width`。
  裁掉那一列不行：装饰只加列、不删列、不重排（D6）。
- **判据**：`InputBoxChromeTest.aRowThatFillsTheTerminalTakesTheRulesOutWithIt`（满行）/
  `aBoxThatDoesNotFillTheTerminalStillYieldsTheLastColumn`（空框，对照组）/
  `aTallBoxSizesItsRulesFromTheRowsItActuallyShows`（滚出框外的行不参与）。
  改回 `width - 1` 第一条立刻红（`expected 60 but was 59`）。

### 缺陷 2 — Shift+Enter 不换行，直接吐出字符

- **现象**：按 Shift+Enter 期望在框里插一个换行，实际把转义序列当文本打进了缓冲区。
- **成因**：**一个换行键都没绑**。「换行」在终端上没有一个键，它是各终端各自编码的组合键，
  JLine 默认一个都不认，于是那串字节落到 `self-insert`。
- **修复**：`InteractiveRepl.bindNewlineKey()`——`cafecli-newline` widget，收下
  `ESC CR` / `ESC LF`（Option+Enter）、`CSI 13;2u` / `CSI 13;5u`（fixterms/kitty 的
  Shift+Enter / Ctrl+Enter）、`CSI 27;2;13~` / `CSI 27;5;13~`（xterm `modifyOtherKeys`）。
  续行提示符由 JLine 默认的 `"%M> "` 改成两个空格，与首行 `> ` 对齐——否则框里每行都顶一个 `>`，
  读起来像好几次提交。
- **明码标价的代价**：**Terminal.app 默认给 Shift+Enter 发的就是裸 `CR`，与 Enter 逐字节相同**，
  进程里无法区分。那一档只能在终端里把 Shift+Enter 改绑成 `\033\r`，或改用 Option+Enter。
- **判据**：`InteractiveReplTest.shiftEnterAndOptionEnterInsertANewlineInsteadOfSubmitting`
  （三种编码各跑一次，断言**提交出来的那一句**里含换行）+ 对照组 `plainEnterStillSubmits`
  （换行绑定不能把 Enter 自己变成插入换行）。摘掉绑定第一条立刻红。

### 缺陷 3 — 退格穿过多行删除不完全

- **现象**：多行 CJK 输入退格清空后，旧的输入行仍留在屏上，下横线不见了。
- **成因**（与缺陷 1 同源，但后果严重得多）：行编辑器按终端整宽折行 ⇒ 一行填满时光标落在
  `col == columns` 这个**右边缘**位置。移到右边缘没有可移植的转义，JLine
  （`Display.moveVisualCursorTo`）改为**重打第 `columns - 1` 列那个字符**。那一列若是**双宽字符的
  后半**，`columnSubSequence(columns-1, columns)` 取回来的是**整个字**（切不开），打在倒数第二列就多占
  一列、触发终端折行，后半落到下一行第 0 列。**从此 JLine 的光标模型与屏幕差两列**，后续每一帧都
  写错位置——下横线从第 2 列开始画、旧输入行再也擦不掉。实测字节：
  `<ESC>[K <ESC>[4A <ESC>[39C <ESC>[48;5;236m的<ESC>[0m`。
- **修复**：`LiveRegionStrip.composedCursorPos` 把光标列夹到 `width - 1`。这是活区那条既有规则
  （「凡是补齐到整行的东西都少占一列」）在**光标**上的实现——此前只靠「我们补的行都短一列」间接
  成立，而输入行不是我们补的。代价：内容恰好填满一行时光标压在末字符上而不是它右边，那本来就是
  延迟折行终端上的歧义位置。
- **判据**：`InputBoxLiveRegionTest.backspacingAcrossWrappedCjkRowsLeavesNothingBehind`
  （真 `CafeCliLineReader` + 真 `InlineRenderer` + `TerminalScreenModel`）。
  **必须读屏**：那些行一行不少地写出去了，只是写错了列，所以字节断言全都成立。
  **ASCII 不复现**（重打一个单宽字符是无害的），这个不对称本身就是判据的一部分——
  任何预测 ASCII 也该失败的解释都是错的。

## 人工验收清单（需要真实终端，Agent 做不了）

前置：`mvn clean package`，然后 `java -jar target/cafecli-1.0-SNAPSHOT.jar`。
**注意 `RenderReproDriver` 尚未为本形态扩展**，所以下面走的是产品本身。

第一轮（2026-08-28）只覆盖了 2 / 4 / 6 三项的一部分，且都报了缺陷（见上）。**其余全部待做**，
修完之后 2 / 4 / 6 也**需要复验**。

1. **空框**（最重要的一条）—— **待做**。启动后什么都不打。底纹是否在整行可见、让它读起来像
   「一个在等着的输入区」而不是「两条分隔线之间的一个提示符」？若只看得见两条横线，D6 的核心主张
   就没有兑现。
2. **横线** —— **第一轮报了缺陷 1（已修），需复验**。是否连续不断裂、不闪烁？两条横线的右端是否与
   底纹**齐平**？（Terminal.app 无同步输出，撕裂在这里最先显形。）
3. **提交的连续感** —— **待做**。随便提交一句。那片底纹是否读起来像原地上移冻成了 `›` 块，
   而不是文字消失、另一个块出现？
4. **打字与换行** —— **第一轮报了缺陷 2 与 3（已修），需复验**。连续快速打字；用 Option+Enter
   （或你终端上能发出 `CSI 13;2u` 的 Shift+Enter）多插几行；再一路退格清空。
   框是否抖动？光标是否始终跟着输入行？**退格后屏上是否一行旧内容都不剩？**
5. **右提示符**（本 issue 唯一没有离线判据的功能项）—— **待做**。空闲显示 `message / @path / @image`；
   跑一个任务时变成 `steer · esc 停止`；触发一次危险工具审批时变成 `y / n / a`。
   把输入文本打长、逼近右提示符——是否重叠或错位？
6. **CJK / emoji** —— **第一轮部分覆盖（缺陷 1/3 都是 CJK 触发的），emoji 那半仍待做**。
   输入若干中文，再输入几个 emoji（尤其是宽度 2 的）。行尾底纹是否补齐、不留缺口？
7. **短终端阶梯** —— **待做**。把窗口高度依次调到 80×24 / 80×20 / 80×12。
   预期 ≥20 行两条横线、≥12 行只剩上横线、再矮只剩 `> `。
   **同时请判断这两个门槛是否合适**——判据是「[[活区尾]]还剩几行才算有用」。若 24 行时框+状态坞
   已经把转写挤得没法读，门槛就该往上抬。**目前 20 / 12 是拍的，量出来之后请把 ADR 0016 D6 与
   `InputBoxChrome` 里的「拍的」标注一并改掉。**
8. **缩放** —— **待做**。拖动窗口宽度（尤其缓慢逐列拖）。横线是否随新宽度重排、不留参差残行？
   （这一条也补上了上面未验的「宽度变化时重排」。）

## Blocked by

- `.scratch/always-live-input-box/issues/05-capture-display-input-line-into-live-region.md`
