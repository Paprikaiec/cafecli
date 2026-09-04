# 02 — `DiffBlock` 就是写文件工具的结果视图

Status: done
Type: 常规
Blocked by: 01

## Parent

`.scratch/unified-output-taxonomy/PRD.md` · ADR 0017 D4

## What to build

`write_file` 与 `edit_file` 的结果**是** diff，不额外再给 3 行文本预览。

- 两者都经 `persistFileContent` → `writeFileObserver` → `InlineRenderer.appendDiff`
  （`ToolRegistry.java:512` 与 `:795`；`Main.java:327` 是接线点）。新建文件时 `before == null`。
- 01 的统一结果路径**跳过**这两个工具的文本结果（它是 `"文件已编辑: …，共 N 条编辑，替换 M 处匹配"` 这类
  摘要，与底下的 diff 说同一件事，显示两遍是噪音）。
- `DiffBlock` 的 100 源行上限与 `… +N 行 (ctrl+o 查看全部)` 溢出行**不变**。
- 头与 diff 归属同一个[[工具块]]：调用头 `Update <path>` 之后直接是 diff 行，不是两个块。

## `create_project` 不发 diff，这不是要补的洞

它用裸 `Files.writeString` 写脚手架（`ToolRegistry.java:1149-1164`），不走 `persistFileContent`。**保持
现状**：它不是文件**变更**工具，是脚手架生成器，四份 `pom.xml` 样板的 diff 是噪音。它按普通工具拿 3 行
文本结果（01 的路径）。

> 本 issue 第一轮 grill 时曾把它记成「另一个方向上的不一致」，随后撤回。**不要**"顺手"给它接上 write
> observer。

## 于是 100 行不是例外

「结果的行数上限按结果类型定」——文本 3 视觉行，diff 100 源行。这是同一条规则的两半，不是钉在 3 行规则上
的一个特例。实现时不要写成 `if (isDiff) skipCap`。

## Acceptance criteria

- [x] `edit_file` 一次调用产生**一个**块：调用头 + diff，没有额外的文本结果行
      —— `ScreenTruthRegressionTest.anEditIsOneBlockOfCallHeaderThenDiffWithNoTextSummaryOnTopOfIt`
      （屏幕真相，带「同一串事件不插帧」对照组；断言头与 diff 之间 0 行空行、`文件已编辑` 0 次）
- [x] `write_file` 新建文件（`before == null`）同形，diff 全为新增行
      —— `ScreenTruthRegressionTest.writingANewFileShowsADiffOfAdditionsAndNoTextSummary`
      （`Update notes.md  +2 -0`，`文件已写入` 0 次）
- [x] `create_project` 产生调用头 + 3 行文本结果，**没有** diff——对照组用例，防止被"顺手"接上
      —— `ScreenTruthRegressionTest.createProjectShowsATextResultAndNoDiffAtAll`
      （已验证有牙：临时给它加一句 `appendDiff` 立刻红）
- [x] `DiffBlock` 的 100 源行上限与溢出文案未被改动（既有回归仍绿）
      —— `DiffBlockTest` 9/9 原样绿；块内那份由
      `ToolCallBlockTest.aDiffResultKeepsItsHundredSourceLineCapInsteadOfTheThreeRowTextCap` 钉着
- [x] 结果上限的选择读起来是「按类型分派」而不是「diff 的特例」（代码复核项，写进 PR 描述）
      —— `ToolCallBlock.ResultView`（sealed，`TextResult` / `DiffResult`）各自回答自己的上限，
      `rows(header, columns, foldState)` 只遍历视图；全文没有 `isDiff` 分支

## 一处本 issue 未指定、实现时定下的

**「跳过文本结果」的判据是两条而不是一条**：工具是走 `persistFileContent` 的那两个之一，**且**这一
`path` 的 diff 真的到了。只按工具名跳，会让写**失败**的调用把 `"写入文件失败: …"` 一起丢掉——那时
observer 根本没发过 diff，调用头底下会空无一物。判据：
`ToolCallBlockTest.aWritesTextSummaryIsDroppedOnlyWhenItsDiffActuallyArrived`（自带对照组）。
顺带把 `edit_file` 补进调用头的 `path` 键与中文标签，此前它落到 default 分支、把整串参数 JSON 打在头上。
