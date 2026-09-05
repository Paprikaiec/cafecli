# PRD: 上 CI 之前要先决定那 6 条红测试怎么办

Status: 6 条已修（走的是选项 A，不是推荐的 B）· CI workflow 已落地（issue 02），但**一次都没在 GitHub 上真的跑过**——仓库还不存在
Date: 2026-09-03
起因: 公开发布前的仓库审计——仓库没有任何 `.github/`，而 `mvn test -Pquick` 在基线上就是红的

> **2026-09-03 更新：下面「已实测」一节里对 A 组的定性被推翻了，改动见本节末尾的「结论」。**
> 保留原文是因为它记的**测量**（哪几条红、在哪些提交上红）仍然成立，被推翻的是从那些测量里读出的
> **因果**。两组的真实根因都不是渲染行为，而是**测试判据读到了宿主环境 / JVM 全局**：
>
> - **A 组（5 × `InlineRendererTest`）**：`TerminalCapabilities.supportsSynchronizedOutput()` 在
>   `terminal.getType()` 不认识时会回退去读进程的 `TERM_PROGRAM` 环境变量——那是**跑 Maven 的那个
>   终端**，绕过了测试里 `Mockito.mock(Terminal.class)` 造出来的终端。这台机器 `TERM_PROGRAM=ghostty`
>   在名单里，于是每一帧都被 `ESC[?2026h … ESC[?2026l` 包住（每帧 +16 字节），逐字节断言全部对不上。
>   **实测**：`env -u TERM_PROGRAM mvn test -Dtest=InlineRendererTest` → 60/60 全绿；
>   `TERM_PROGRAM=ghostty` 同一条命令 → 5 红。同一份代码，只差一个环境变量。
> - **B 组（1 × `TerminalMarkdownRendererTest`）**：原文「顺序依赖的共享状态」判对了。具体是
>   `AnsiStyle.ENABLED` 是 `static final`、每个 JVM 只算一次，而这个测试类在自己的静态初始化里设
>   `paicli.render.color=false`——**谁先把 `AnsiStyle` 加载起来谁说了算**。单独跑它赢（纯文本），
>   全量套件里 `InlineRendererTest` 先加载它就输（带样式）。判据 `line.length() <= 72` 于是把
>   转义字节也算了进去：**实测**那根纯 ASCII 边框行是 72 列 / 85 字符。
>   顺带一提，这个成因在仓库里**已经被写下来过两次**（`InlineRendererTest:357`、
>   `InputBoxChrome:105`），只是没人把它和这条红测试连起来。
>
> **结论：A 组不是「长期红」，B 组也不是「测试期望过时」，两组都不是渲染缺陷。**
> 下面那张「四个提交上都是 5 红」的抽样表**测量没错，推论错了**——它证明的是「这台机器这一个多月
> 一直是 Ghostty」，不是「代码红了一个多月」。四个采样点共享同一个环境变量，所以对这个变量结构性
> 失明。这正是本仓库反复强调的那件事：**抽样能排除的东西，比它看上去能排除的少。**

## Problem Statement

仓库**没有 CI**（`.github/` 整个不存在：没有 workflow、没有 issue 模板、没有 PR 模板）。对一个自称面向商业使用的项目，这是公开发布后最显眼的缺口——外部贡献者提 PR 时没有任何自动判据。

但**不能直接加 workflow**：`mvn test -Pquick` 在干净的树上就有 6 条失败。照搬一个 `mvn test -Pquick` 的 workflow，第一次推送就是红的，而「CI 常红」比没有 CI 更糟——它训练所有人忽略红灯，之后真的回归了也看不见。

所以顺序是**先处理这 6 条，再加 CI**，不能反过来。

## 已实测（2026-09-03，非推断）

基线 `a1e52e3`：`mvn test -Pquick` → **1281 tests, 6 failures**（其中 6 条是本次新增的 `SnapshotRestoreStaysInsideProjectRootTest`，全绿；失败数在新增前后都是 6，未变）。

两组失败，**性质完全不同，不要一起处理**：

### A 组：5 × `InlineRendererTest`，长期红

```
coalescedNextTurnAnswerSurvivesThePreviousTurnFinishCutoff:1767
nextTurnStartsItsLiveStripFromAReanchoredDisplay:1390
readingPushIsIncludedInRenderWriteByteAndFlushAccounting:1800
submittedPromptFreezesAsAWashedBlockWithChevronOnlyOnItsFirstRow:2427
turnEndEmitsOneStatsLineWhenEnabled:949
```

在这些点上逐一跑过，**同样的 5 条、同样的报错**：

| 提交 | 日期 | 结果 |
|---|---|---|
| `0fc87b4` 包名迁移 | 2026-07-29 | 60 tests / 5 failures |
| `0160d0e` 渲染器 import | 2026-09-01 | 60 tests / 5 failures |
| `3003e1d` | 2026-09-02 | 60 tests / 5 failures |
| `a1e52e3` HEAD | 2026-09-03 | 60 tests / 5 failures |

即**至少红了一个多月，且早于渲染器 import**。第一个坏提交**没有二分定位**——上表是四个点的抽样，不是 bisect 结果，别当成「起点是 0fc87b4」引用。

### B 组：1 × `TerminalMarkdownRendererTest.wrapsWideMultiColumnTableInsideTerminalWidth`

**单独跑是绿的**（在 `3003e1d` 上实测 13/13 全绿），只在全量套件里红。即顺序依赖的共享状态，不是这条测试本身错。**别用「单独跑一下发现是绿的」来判定它没问题**——那正是它的症状。至今未立 issue。

## 要做的决策 → **已定：走 A，6 条全修好，一条都没隔离**

原本列的三种形状：

| | 做法 | 代价 | 风险 |
|---|---|---|---|
| **A**（**最终选择**） | 修好 6 条，CI 全绿门 | 最贵；A 组是渲染器深水区，且 ADR 0016/0017 的真机验收本就欠着 | 时间不可控，可能把发布拖住 |
| **B**（当时推荐） | `@Disabled` 隔离 + 每条挂一个 issue 链接，CI 全绿门 | 便宜、诚实、可回收 | 隔离容易变成永久遗忘，必须有 issue 兜住 |
| **C** | CI 允许已知失败（白名单 / `-Dmaven.test.failure.ignore`） | 最便宜 | **不要选**——白名单会静默吸收新的失败，等于没有门 |

**推荐 B 的前提被根因推翻了。** B 之所以看着划算，是因为当时以为 A 组要下渲染器深水区。根因查明之后 A 组根本不在深水区——它连渲染代码都不用碰，只要让测试别再读宿主终端。于是最贵的选项变成了最便宜的：**实际改动是 pom 一个属性 + 一个测试类的断言方式**，没有 `@Disabled`，没有新欠的票。

**教训（比结论更值钱）**：A/B/C 这张表是在**根因未知**时列的，三个选项的代价估算全部继承了那份未知。「先查便宜的那组」这条建议本身是对的，但当时把 A 组估成深水区、B 组估成浅水区，实际上**两组一样浅**。碰到「隔离还是修」的岔路，先花一小时定根因再估代价——这次那一小时把「最贵」变成了「最便宜」。

## CI 该跑什么

- `mvn test -Pquick` 是主门。`-Pquick` 已排除外部进程 / 网络 / 超时路径（`McpServerManagerTest` / `StdioTransportTest` / `StreamableHttpTransportTest` / `ToolRegistryTest` / `WebFetcherTest`），适合 CI。
- **`-Pphase16-smoke` 绿不代表渲染没问题**（`AGENTS.md`）：屏幕真相判据不在那个 profile 里，要按名字单独跑——`ScreenTruthRegressionTest` / `InputBoxLiveRegionTest` / `ToolCallBlockTest` / `CaptureDisplayInterceptionGuardTest` / `HardTerminationCouplingTest`。
- **矩阵里要有 macOS**，不能只有 `ubuntu-latest`。`PathGuard` 里 `/var` → `/private/var` 那条真实路径规范化**只在 macOS 上被走到**（`@TempDir` 就在 `/var` 下），而快照恢复围栏整个建在它上面。Linux-only 的矩阵对那条路径结构性失明。
- Java 21+（虚拟线程是硬要求，`pom.xml` 里 source/target 都是 21）。
- 需要系统 `git`（`SnapshotDoesNotTouchProjectGitTest` 用它造 linked worktree fixture，拿不到就 `assumeTrue` skip——CI 上必须**真的跑**，别让它静默 skip 过去）。
- 需要能建符号链接（`SnapshotRestoreStaysInsideProjectRootTest`）。Linux / macOS runner 都可以；Windows runner 会 skip。
- **runner 上不要设 `NO_COLOR`**，设了就是 2 红 + 2 静默 skip——见下面「已知边界」。
- **`TERM` 必须钉死**（写这份 PRD 时漏了，落地时实测撞上）。`AnsiStyle.ENABLED` 与 `TerminalCapabilities.supportsAnsi()` 都读进程的 `TERM`：unset / `xterm` → 开，`dumb` → 关。实测 `-Pquick`：`TERM=xterm` 1283/0 fail/0 skip，`TERM=dumb` **101 fail + 18 error + 2 skip**（`ScreenTruthRegressionTest` 44 + `InlineRendererTest` 37 打头）。`COLORTERM=truecolor` 实测无影响。所以 job 里写死 `TERM: xterm`，而不是听凭 runner 镜像。这和 pom 里 `${test.argLine}` 钉死那两项终端能力是同一件事的两半。

## 隔离数量

**0 条。** PRD 原本要求「隔离数量写进 PRD，别让它悄悄涨」——最终一条都没隔离，6 条全部真修好了。

## Issues

| # | 标题 | Status |
|---|---|---|
| 01 | 给 6 条红测试定性并落隔离策略 | **done**（按选项 A 修好，0 条隔离）|
| 02 | 加 CI workflow（依赖 01） | **done**（2026-09-05）——两个平台都在真机上跑过了 |
| 03 | `InteractiveReplTest` 挂死：Ctrl+D 与回合结束的竞态 | **ready-for-agent**（兜底已落地，竞态未修）|

## 已知边界（修完之后仍然成立）

- **`NO_COLOR=1` 下 `-Pquick` 仍是红的：2 红 + 2 静默 skip**（实测 2026-09-03，用户裁定先记录不修）。同一类缺陷、不同开关：`NO_COLOR` 让 `AnsiStyle.ENABLED=false`，输入框走 `InputBoxChrome.Palette.PLAIN` 那一档，横线由 `─` 退化成 ASCII `-`（那一档承诺「一个转义字节都不产生」，是有意设计），而 `ScreenTruthRegressionTest` 两条把 `─` 写死了；同时 `AnsiStyleTest` 两条 `assumeTrue(isEnabled())` 静默 skip。**产品行为没坏，坏的是判据。** 修法照 `InlineRendererTest:360` 的成例：从 `InputBoxChrome.Palette` 推出该期望哪根横线，不要写死字面量。已记进 `AGENTS.md`「当前状态与已知边界」。
- **色彩档位仍然由宿主环境决定**（`TERM` / `NO_COLOR`），只是不再由**加载顺序**决定。故意没把 `paicli.render.color` 一起钉进 `${test.argLine}`：实测钉成 `false` 会让上面那 2 条变红 + 2 条静默 skip，等于用一个假绿换一个真绿。

## Non-goals

- 不在这一条里修渲染器行为本身——**事后看这条根本没被触发**：6 条一条都没要求改渲染代码，改动全在测试判据与 surefire 配置里。
- 不引入 issue 模板 / PR 模板 / `CONTRIBUTING.md`——那些是独立的发布项，与「CI 能不能绿」无关

## 旁边还挂着的（不属于本 PRD）

- **项目级配置的信任模型不对称**（`AGENTS.md`「当前状态与已知边界」有一条）：设计待定，需要先做产品决策再写码，artifacts 不在 main 上。
