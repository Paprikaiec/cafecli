# 13 — 补全菜单收起后留下残行（整行右移一列）

Status: done（离线判据已绿；**未在真机确认**）
Type: 缺陷
Labels: ready-for-human

## Parent

ADR 0014（行编辑器交出画笔）· ADR 0016 D4/D6（[[输入框]]）

## 从哪儿来的

`.scratch/unified-output-taxonomy/issues/04-real-terminal-acceptance.md` 的第一轮真机验收报的三个缺陷
里的第 3 个。当时判断「大概率不属于 ADR 0017」但「必须真机才能确认」。**第二轮已在离线确认，并 bisect
到 `58adeab`**（输入框那条提交，ADR 0017 一行代码都没有），残留逐行相同 —— 所以它归这里，不归那个 tracker。

## 症状

真机（Terminal.app）：`> 读取 @` 之后弹出本地路径补全菜单，选中一项后菜单**没有被擦干净**——`本地路径`
标题压在 `rl+Y to enable HITL`（[[状态坞]]文字）上，候选行与 Banner / 状态坞交错，同一批候选出现两次。

## 离线复现（**已有，且是红的**）

只需要三样东西，都不是 ADR 0017 的：`InlineRenderer` + 真实 `CafeCliLineReader` + 一个返回多个候选的
completer。形态照抄 `InputBoxLiveRegionTest` 的 rig（真实读输入线程 + `ManualFramePump`），判屏用
`TerminalScreenModel`。

- 终端 80×24，先 `r.stream().println(...)` 打 6 行转写，`updateStatus` 让状态坞就位
- completer 返回 24 个候选：`@candN/some/long/path/FileN.java`
- `reader.setOpt(AUTO_LIST)` + `reader.unsetOpt(AUTO_MENU)`
- 键入 `@cand` → `\t`（菜单展开，捕获帧 13 行）→ `1\t` → `x`（菜单收起）

收起之后的屏幕（`[n]` 是该行列数）：

```
  6 [ 79] |───────────────────────────────────────────────────────────────────────────────|
  7 [ 80] |> @cand                                                                         |
  8 [ 79] | ──────────────────────────────────────────────────────────────────────────────|  ← 整行右移一列
  9 [ 79] |@cand1/some/long/path/File1.java     @cand20/…                                  |
 10 [ 79] |         el · glm-5.1  idle  ctx ░░░░░░░░ 0% (0/200.0k)  ~/Project/paicli-tui-r|  ← 状态坞被压
 11 [ 71] |@cand10/some/long/path/File10.java   @cand21/…|
 12 [  9] |         |                                                                         ← 折行碎片
 19 [ 69] |@cand141some/long/path/File14.java   @cand3/…|                                     ← 候选行被写花
 21 [ 69] |@cand1/some/long/path/File1.java     @cand15/some/long/path/File15ava|
```

判据（菜单收起之后）：**屏上候选行数为 0**；实测是 **7**。文档 36 行 / 屏高 24，也就是多出来的行把
转写顶进了回卷。第 10 行就是真机报的那一条。

## 机理（已定位，已量）

**捕获帧的一行不是一行——它带着自己的 `'\n'`。**

`LineReaderImpl.redisplay` 用 `full.columnSplitLength(size.getColumns(), **true**, delayLineWrap, terminal)`
切它要显示的行，第二个参数是 `includeNewlines`：**按换行切出来的那一行会把 `'\n'` 留在行尾**。补全菜单
（`post`）是一整串带内嵌换行的文本（`getDisplayedBufferWithPrompts` 里 `full.append("\n"); full.append(post.get())`），
所以菜单一展开，捕获帧里大多数行——**连同输入行本身**——都以 `'\n'` 结尾。红着的那条判据报的就是
`[> @cand\n]`。

它一路穿透[[活区]]的两个假设：

1. `InputBoxChrome.washed` 逐字符拷贝，于是把 `'\n'` 一起拷进去；而 `columnLength()` 把它算作 **−1** 列
   （实测 `WCWidth.wcwidth('\n') == -1`：同一串 71 个可见字符，不带换行量得 71、带换行量得 70）。所以
   底纹补齐**既多补一列、又补在了换行之后**。
2. `LiveRegionStrip.hardTerminated()` 随后再补一个 `'\n'`，而 `Display.update` 只剥掉**末尾**那一个，
   内嵌的那个原样打给终端。

于是一个「行」占了**两个**物理行。字节流看得一清二楚（菜单展开那一帧）：

```
<ESC>[36;48;5;236m@cand<ESC>[39m10/…/File10.java   <ESC>[36m@cand<ESC>[39m21/…/File21.java<CR><LF>
         <ESC>[0m<CR><CR><LF>          ← 9 个空格 = 79(框宽) − 70(被 −1 算错的列数)
```

而活区所有的行数——[[活区尾]]的预算、`lastRegionRows` 的回退距离——都按一行算，擦除因此永远擦不干净。
（最后一行菜单不带 `'\n'`，它的补齐就正常留在同一行，这个不对称本身就是佐证。）

**先前记在这里的「整行右移一列 = JLine 延迟折行续行编码」是错的**：那一列空格是 `Display.moveVisualCursorTo`
去右边缘时补的，是症状不是成因。真正的成因是上面这条，与 ADR 0016 那个「光标永不落在最后一列」缺陷
**无关**（那次修的是光标，这次是行）。

## 修法

`CaptureDisplay.Frame` 的构造期**逐行去掉换行**（保留每个字符自己的样式；不含换行时原样返回、不分配）。

**在捕获边界去而不是在活区里去**：活区里其余的行全是我们自己排的、按构造满足「一行一个物理行」，
唯一的外来行从这里进来。去掉之后 `columnLength()` 重新等于可见列数，`washed` 的补齐那处不必再单独设防
——所以这是**一处**改动，不是两处。

**明码标价的代价**：菜单收起后 JLine 有时仍留一个空的 `post`，捕获帧因此多一个空行，框里会多一行空的
底纹行。**没有一并去掉尾部空行**，因为进程里分不清它与「用户自己按 Alt+Enter 打出来的空尾行」——后者
是用户真的该看见的行（光标可能就在上面）。

## 已排除（都是量出来的，不是推的）

- **`BlockSource.clip` / `ProseEntityHighlighter.wrap` 不会超出列宽**：columns 10–20 × ASCII 前缀 0–4
  全扫 CJK 串，一次都没超。所以不是「某个块的行比终端宽」。
- **`InputBoxChrome.boxWidth` 的「上界取可见行最大列宽」豁免不是成因**：把它硬夹回 `width - 1` 之后
  屏幕**逐行不变**，第 7 行仍然是 80 列。那第 80 列不是我们补出来的。

## 判据（两条，都在 `InputBoxLiveRegionTest`，都**先红后绿**）

- `aClosedCompletionMenuLeavesNoCandidateRowsOnScreen` —— **读屏**：菜单收起后屏上候选行为 0、状态坞
  恰好一份。修复前 `expected: <0> but was: <7>`。判据必须读屏，因为那些行确实被写出去了、只是多占了行，
  所以字节断言全都成立（与 `backspacingAcrossWrappedCjkRowsLeavesNothingBehind` 是同一种不对称）。
- `noCapturedInputRowEverContainsALineBreak` —— **读帧**：捕获帧里没有任何一行含换行，且每一行的
  `columnLength()` 等于它的字符数。这是修复建立的那条不变式本身；单靠屏幕干净证明不了它成立。

`Rig` 加了一个可传 `Completer` 的构造器（原两个构造器行为不变）。

## 状态

离线全绿：`InputBoxLiveRegionTest` 10/10；`ScreenTruthRegressionTest` 56、`ToolCallBlockTest` 14、
`InputBoxChromeTest` 25、`CaptureDisplayInterceptionGuardTest` 4、`ResidentInputRegimeTest` 11、
`LiveRegionStripTest` 16、`StripEntryContractTest` 5、`OverlayDocumentTest` 8、`SteeringPositionTest` 5、
`OrderedCommitLogTest` 2 —— 合计 146/146。`mvn test -Pphase16-smoke` 453/5，**5 个失败与 `master` 上
逐名相同**（`InlineRendererTest` 那五条，早于本分支，见该 profile 的已知基线）。

**未在真机确认。**真机要看的是：`@` 打开本地路径补全菜单、选一项、看菜单有没有被擦干净，以及框里
那一行多出来的空底纹行读起来是否可接受（见上文「明码标价的代价」）。

## 顺带

同一轮还查了 `.scratch/unified-output-taxonomy` 的缺陷 2（运行中[[工具块]]留副本）：400 个随机场景 ×
6 种几何量**复现不出来**，而 fuzz 本身被验证过有分辨力（拿掉 `clearLiveRegion` 则 400/400 全红且形态
一致）。细节写在 issue 04 的 `## Comments` 里。两者若真同源，修了本条说不定那条就跟着没了。
