# 03 — `execute_command` 运行中的输出尾

Status: done（2026-08-31；离线判据齐全，**未在真机确认**——手感与 5 这个数的测量归 04）
Type: 常规（离线判据齐全；手感归 04）
Blocked by: 01

## Parent

`.scratch/unified-output-taxonomy/PRD.md` · ADR 0017 D5

## What to build

**只有 `execute_command` 有运行中的尾。**

1. **`ToolRegistry.readProcessOutput`（`ToolRegistry.java:2319`）加一个逐行 observer。** 它今天在专用线程
   （`cafecli-command-output`）上逐行读进 `StringBuilder`，但直到 `waitFor` 返回之前什么都不发布——缝就在
   那个 `while ((line = reader.readLine()) != null)` 里。这是本 PRD 唯一一处工具层改动。

2. **活区里显示最后 5 个非空行**，随命令输出滚动。并发命令**共享** 5 行总预算：一条命令占 5 行，四条各占
   1 行（默认并行度 4）。

3. **命令结束时这片尾巴被 01 的 3 行完成态结果头部替换掉**——不是追加，是替换。运行中显示的是**尾**
   （最后 5 行），完成后显示的是**头**（前 3 视觉行），两者内容不同是有意的。

4. observer 必须走渲染事件、**不得直写终端**：它跑在 `cafecli-command-output` 线程上，而渲染线程是活区
   唯一的写者。

## 这不是被否决的延迟冻结

它长得像 ADR 0012 否决的那个东西（为了原地展开而把块留活），但不是：**那里被留活的块已经完成**，这里的块
**还没有结果**。前者是把不可变的东西按住不放，后者是瞬时内容待在瞬时内容该待的地方。判别式是「这个块完成
了没有」。这句话请写进代码注释——它看上去就是被否决的那个，下一个读代码的人会以为是缺陷。

## 5 行是拍的，不是量的

判据应是「[[活区尾]]还剩几行才算有用」——它同时被[[状态坞]]、[[输入框]]与 [[bounded replay window]] 挤压。
与 ADR 0016 那两个 20 / 12 是同一笔未做的测量。**不要把它写得像实测值**；要么在 04 里按 80×24 量一遍改成
量出来的，要么在代码注释里明说它是拍的。

## Acceptance criteria

- [x] 命令运行期间，活区尾显示它最后 5 个**非空**行，新行到达时滚动
      —— `ScreenTruthRegressionTest.aRunningCommandShowsItsLastFiveNonEmptyOutputLines`（屏幕真相）
      + `ToolCallBlockTest.aRunningCommandTailKeepsOnlyItsLastFiveNonEmptyLines`；
      逐行发布本身钉在 `ToolRegistryTest.shouldPublishCommandOutputLineByLineWhileTheCommandIsStillRunning`
      （命令**依赖**于自己被观察到才打印第二行，所以「等 waitFor 返回才发布」这一档拿不到 exit 0）
- [x] 4 条并发命令各占 1 行，总计不超过 5 行
      —— `ScreenTruthRegressionTest.concurrentCommandsShareTheFiveRowBudget`
      + `ToolCallBlockTest.concurrentCommandsShareTheBudgetOneRowEach` /
      `moreCommandsThanRowsLeavesTheBudgetIntact`（>5 那一格 ADR 未指定：先开口的先占）
- [x] 命令结束后运行尾**消失**，位置上是完成态的 3 行结果头部
      —— `ScreenTruthRegressionTest.aFinishedCommandsRunningTailIsGoneFromTheScreen`（对照组：同一条
      命令一行没打过，两块屏逐行相等）+ `theRunningTailIsReplacedByTheCompletedResultHead`（尾 vs 头）。
      把 `emitPendingScrollback` 的 `clearLiveRegion()` 摘掉即红——那正是「擦掉与滚走字节相同」的那一档
- [x] 只有 `execute_command` 有这个行为（对照组）
      —— 工具层 `ToolRegistryTest.onlyExecuteCommandPublishesRunningOutput`（`read_file` / `grep_code`
      一次都不触发 observer，这是「只有它」被决定的那一处）+ 屏幕层
      `ScreenTruthRegressionTest.onlyACommandGrowsTheLiveTailWhileItRuns`（两块屏恰好差 5 行）
- [x] observer 不在 `cafecli-command-output` 线程上写终端
      —— `ScreenTruthRegressionTest.theCommandOutputThreadWritesNothingItselfTheRenderThreadDoes`：
      从一个真名为 `cafecli-command-output` 的线程调用，返回时终端字节数**没变**，下一帧才上屏。
      比 grep 强，因为它断言的是行为而不是某个 API 名字没出现
- [x] 命令超时被 `destroyForcibly` 时运行尾同样清干净，结果为超时消息
      —— `ScreenTruthRegressionTest.aTimedOutCommandLeavesNoTailAndShowsTheTimeoutAsItsResult`
      + `ToolRegistryTest.aCommandKilledOnTimeoutStillPublishedWhatItPrintedFirst`
      + `ToolCallBlockTest.theRunningTailIsReplacedWhenTheResultsArrive`（晚到的行被丢弃）
- [x] `mvn test -Pphase16-smoke` 全绿
      —— 453 tests / 5 failures，与 `master` 逐条同名（`InlineRendererTest` 那五条，先于本分支存在）
