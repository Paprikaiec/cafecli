# 11 — 条目是不可变源，渲染缓存归视图

Status: done

Delivered: 2026-08-27 (ADR 0016 D1, «Delivered and measured» 小节)。

## Parent

`.scratch/always-live-input-box/PRD.md`（ADR 0016 D1；修订 ADR 0010 A13）

> **本 issue 取代了原来的「回合中 Ctrl+O：渲染线程快照 + 挂起协议」。** 那件事已经由
> destructive-resize-epoch 的 issue 06/07/08/09 交付了，但走的是另一条路：[[模态租借]]（A12）替下了挂起
> 协议，实时跟随（A13）替下了一次性快照，审批关层（A14）**推翻**了「审批不强制关闭浏览层」。剩下的
> 是原 issue 唯一没有被那条路回答的东西——**它当初的安全论证**。鼠标追踪的时序陷阱移到 07（成因在那里）。

## What to build

**日志里的条目只暴露不可变的源；把源排成某个宽度的行、以及为此存下的缓存，归看它的那个视图所有。**
[[活区]]一份（渲染线程），[[转写浏览层]]一份（输入线程），互不共享。

### 为什么这是个 bug 而不是重构

原 issue 写下过这句话：「浏览层现在从输入线程直接读[[保留窗口]]里的块对象并调它们的渲染方法，而渲染
线程正在并发修改同一批块、并往它们**无同步的**渲染缓存里写。今天安全仅仅因为浏览层只在空闲时开。
回合中开即是对哈希表的数据竞争——是损坏，不是画面撕裂。」

A13 交付了「回合中开层」，但既没有走快照、也没有加锁。**所以这条竞态现在活在 `main` 上。**

**共享是读代码证实的；症状是推出来的，未被观察到。** 用户在真机手测中**没有**见过「开着浏览层时
Cafecli 消失」，也**没有**见过滚动浏览层导致的卡顿（2026-08-26 确认）。所以：竞态确实在，但命中率低。
本 issue 因此按**常规节奏**做，不是 hotfix——修它的理由是共享本身，不是某次崩溃。也**不要**为了
「先复现」花力气：一个数据竞态即使复现成功，证明的也是 Java 的通用事实，而不是这个产品的什么性质。

- `AnswerSegment` 的 `projections` 与 `highlightedProseCache` 是裸 `HashMap`（`AnswerSegment.java:21-22`），
  投影内部还有 `ArrayList`。
- 渲染线程每帧经 `drainCompletedAnswerRows` 写它们（`LiveRegionStrip.java:557`）——**这一步刻意跑在
  [[geometry turbulence]] 闸之上**，所以[[模态租借]]期间它照跑（这正是实时跟随成立的原因，不是 A12 的缺陷）。
- 输入线程每 50ms 轮询经 `OverlayDocument.renderItem` → `entry.render(width, fold)` 读**并写**它们
  （`OverlayDocument.java:290-293`）。
- `ReplayLog` 同步的是**列表**；`List.copyOf` 交出去的是它并不保护的条目引用。

最坏情况：`HashMap` 扩容与 `put` 撞上留下环形桶，**输入线程**在 `get` 里 100% 空转——而此刻终端在
alternate screen，读按键的正是它，没人能接 Ctrl+C。次坏：`wrappedCompletedLines.get(i)` 与 `sync()`
的 `add` 撞上，在同一条线程上抛 `IndexOutOfBoundsException`。

**同根的第二个缺陷（不是竞态，就是错）**：`OverlayDocument` 把离开工作集的条目
`releaseRenderCache()`（`:281`、`:310`），对流式答案而言这会清掉**活区的** `MarkdownProjection`，连同
`consumedSourceLines` 与累积的 `TerminalMarkdownRenderer`。于是在浏览层里向上翻一下，渲染线程下一帧
就得把整个答案重新过一遍 markdown。只有「一份缓存服务两个视图」才可能这样。

### 怎么做

- `AnswerSegment` 只留追加式的源，并把它以**不可变快照经 `volatile` 引用发布**，每完成一行替换一次。
  那个引用就是 happens-before 边；读者拿到的要么是最新的一致快照、要么是更旧的一致快照，永远不是撕开的。
  代价是每完成一行拷一次数组。
- `MemoizedBlock` 留下它的源行（本来就是 `List.copyOf`，本来就可以共享），**交出 `renderedByKey`**。
  缓存移到视图：活区一份，`OverlayDocument` 一份（键是「条目身份 + 宽度 + 折叠态」）。它本来就在跟踪
  工作集，所以释放变成纯本地操作，**按构造正确**而不是碰巧不出事。
- [[冻结边界]]（`frozenSourceLines`，A11）**留在条目上**：它是活区自己的视图状态（哪段前缀已提交），
  浏览层渲染的是整条保留条目，根本不问。A11 不受影响。

`releaseRenderCache()` / `renderCacheEntries()` 随之离开 `StripEntry` 接口；
`ReplayLog.renderCacheEntries()`（同步的，却伸手进它并不拥有的条目）一并删除；
`InlineRenderer.retainedLogSnapshot()` 的缓存计数改为按视图统计。

## Acceptance criteria

- [x] 输入线程在任何路径上都不再调用渲染线程拥有的条目的可变方法（可 grep + 结构判据：`StripEntry`
      接口上不再有 `releaseRenderCache` / `renderCacheEntries`）
      —— `StripEntryContractTest.theEntryInterfaceCarriesNoRenderCache`；浏览层改走
      `RetainedRenderCache`，`ReplayLog.renderCacheEntries()` 已删
- [x] `AnswerSegment` 的源以不可变快照经 `volatile` 发布；条目上不再有跨线程可变的 `HashMap` / `ArrayList`
      —— `AnswerSource` + `volatile AnswerSource source`；投影搬进 `AnswerProjection`(s)，由
      `LiveAnswer`（渲染线程）与浏览层各持一份
- [x] **回合流式输出中开着浏览层反复上下翻，活区的 markdown 状态不被清空**
      —— `ViewOwnedRenderCacheTest.scrollingTheOverlayDuringAStreamingTurnDoesNotReplayTheLiveAnswersMarkdown`；
      修复前实测 prose 高亮遍数 40→440、缓存行 80→0，修复后恒 40
- [x] 浏览层与活区在同一宽度下各自持有缓存，互不释放对方的
      —— `ViewOwnedRenderCacheTest.theTwoViewsHoldTheirOwnRowsAtTheSameWidthAndNeitherReleasesTheOthers`
- [x] **成本实测而非断言** —— `OverlayLayoutCost`，2000 轮 / 6000 条目，各 n=3 次跑：开层 35–36 → 39–41 µs、
      开层落末尾 16.6–17.4 → 16.4–16.8 ms、单次按键 9–10 → 8 µs、`G` 首跳 16.4–16.9 → 16.3–16.4 ms、
      空轮询 13 → 12 µs、流式跟随 149–152 → 144–148 µs（为此给具加了两列，因为原来的合成会话里
      一条 `AnswerSegment` 都没有）。无数量级劣化
- [x] 一条回归钉住规则本身：给某个 `StripEntry` 实现加上"提交后仍可变"的状态即变红，**与当时谁在读无关**
      —— `StripEntryContractTest.renderingARetainedEntryMutatesNothingOnIt`（反射指纹，8 种保留条目）
      + `theRuleCatchesAnEntryThatGainsMutableStateAfterCommit`（反例桩）
      + `everyEntryTypeIsEitherCheckedOrExplicitlyExcluded`（新条目类型不能悄悄逃掉）
- [x] `mvn test -Pquick` 与 `mvn test -Pphase16-smoke` 全绿 —— smoke 414/414；quick 1270 条 1 失败，
      为既有的顺序相关 flake `TerminalMarkdownRendererTest.wrapsWideMultiColumnTableInsideTerminalWidth`
      （改动前同一命令同样失败，见报告）
- [x] 文档同步：AGENTS.md 中保留窗口 / 浏览层视口排版的条目（渲染缓存的归属），新增[[条目源与视图缓存]]
      一条；CONTEXT.md 同名词条对齐已交付形态；ADR 0016 D1 补「Delivered and measured」小节

## Blocked by

无。这是对**已交付代码**的修复，竞态现在就活在 `main` 上，不应排在 07 之后。
