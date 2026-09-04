# 02 — 加 CI workflow

Status: **workflow 已落地（2026-09-04）；两条判据欠着真机**——GitHub 上还没有这个仓库，没有任何一次 CI 真的跑过
Type: 基础设施
~~Blocked by: 01~~ —— **01 已 done（2026-09-03），阻塞解除**。`-Pquick` 现在在干净树上是 1283 / 0 fail / **0 skip**，且在模拟 CI 的环境变量下（`TERM` / `TERM_PROGRAM` / `COLORTERM` 全 unset）同样全绿。

## Parent

`.scratch/ci-and-test-quarantine/PRD.md`

## 形状

`.github/workflows/ci.yml`，push + pull_request 触发。

矩阵：`ubuntu-latest` + `macos-latest`，JDK 21（`temurin`，带 maven 缓存）。

**macOS 不是可选的**：`PathGuard` 的 `/var` → `/private/var` 真实路径规范化只在 macOS 上被走到（`@TempDir` 在 `/var` 下），而快照恢复围栏整个建在它上面。Linux-only 的矩阵对那条路径结构性失明。

步骤：

1. `mvn -B test -Pquick`
2. 单独跑屏幕真相判据（**不在 `-Pphase16-smoke` 里**）：`ScreenTruthRegressionTest` / `InputBoxLiveRegionTest` / `ToolCallBlockTest` / `CaptureDisplayInterceptionGuardTest` / `HardTerminationCouplingTest`
3. `mvn -B clean package` 确认产物能出

**runner 上不要设 `NO_COLOR`**（issue 01 实测：设了就是 2 红 + 2 静默 skip，成因与修法见 PRD「已知边界」）。GitHub 的 runner 默认不设，但如果给 job 加 env 记得别顺手加上。

## 两个坑

- **`assumeTrue` 会把 skip 伪装成 pass。** `SnapshotDoesNotTouchProjectGitTest` 没有系统 git 就 skip，`SnapshotRestoreStaysInsideProjectRootTest` 建不了符号链接就 skip。CI 上这两条必须**真的跑**——加一步断言 skip 数为 0，或者至少把 surefire 的 skipped 计数打出来看。绿灯里混着 skip 是最难发现的假绿。
- **不要用 `-Dmaven.test.failure.ignore`**，也不要白名单已知失败。那样新的失败会被静默吸收，门就不是门了。

## 判据

- [ ] 两个平台都绿，且 skipped = 0 —— **只验了 macOS 本机**，ubuntu 与「两个平台」都要等仓库真的存在
- [x] 故意改坏 `SideGitManager` 的围栏 → CI 变红（门是活的，不是装饰）—— 本机验了，但**只有三层里的一层是活的**，见下
- [x] `README.md` 顶部加 CI badge

## 落地结果（2026-09-04）

### 交付物

- `.github/workflows/ci.yml`
- `.github/scripts/assert-no-skipped-tests.sh`（skipped = 0 那一步，可本机跑，不是只能在 CI 里跑的逻辑）
- `README.md` 顶部 badge，指向 `Paprikaiec/cafecli`

### 第三个坑：`TERM`（写 issue 时没想到，实测撞上）

`AnsiStyle.ENABLED` 和 `TerminalCapabilities.supportsAnsi()` 都读进程的 `TERM`：unset / `xterm` → 开，`dumb` → 关。这不是边角差异——同一份代码，`-Pquick` 实测：

| env | 结果 |
|---|---|
| `TERM=xterm` | 1283 / 0 fail / 0 skip |
| `TERM=dumb` | 1283 / **101 fail + 18 error** / 2 skip |
| `TERM=xterm COLORTERM=truecolor` | 1283 / 0 fail / 0 skip |

`dumb` 那一行的 119 条里，`ScreenTruthRegressionTest` 44 条、`InlineRendererTest` 37 条。**runner 镜像给不给 `TERM`、给什么值，不该决定这道门的颜色**，所以 job 里钉死 `TERM: xterm`（不是 `xterm-256color`——那会把 `AnsiStyle.COLORS_256` 翻成 true，是另一档配色）。这与 pom 里 `${test.argLine}` 钉死那两项终端能力是同一件事的两半。

`COLORTERM=truecolor` 实测无影响，所以没为它多加一个钉子。

### skipped = 0 的现状

本机 `-Pquick` 是 **172 suites · 1283 tests · 0 skipped**，那三处 `assumeTrue`（系统 git / 符号链接 / `AnsiStyle.isEnabled()`）都**真的跑了**。脚本的四个分支都验过：干净报告 → 0；有 skip → 1 并逐类打印；有 failure/error → 1；报告目录空或不存在 → 1。

`-Dtest=...` 那一步还白捡一条性质：surefire 的 `failIfNoSpecifiedTests` 默认为 true，**实测**把类名写成不存在的 `ThisClassDoesNotExistTest` 会直接让构建红。所以哪天这五个屏幕真相判据被改名或删掉，CI 会喊，而不是无声地少跑一批。

### 「门是活的」只验到一半 → 新开了 issue

按判据去改坏 `SideGitManager` 的围栏，**第一刀砍空了**：把 `contain(String)` 里的 `guard.resolveSafe(...)` 换回修复前那条词法 `resolve().normalize()`，6 条全绿。逐层变异之后才看清——恢复围栏有三层，只有 file mode 分支那一层被测试钉住：

| 变异 | 30 条快照 / 围栏测试 |
|---|---|
| `RestoreFence.contained()` 无条件放行（围栏形同不存在） | **全绿** |
| 删掉 `FileMode.SYMLINK` 分支 | **1 红** |
| 删掉末段 `Files.isSymbolicLink(file)` 跳过 | **全绿** |

所以这条判据的答案是：**能红，但只在改坏 mode 分支时能红。** 覆盖缺口另立
`.scratch/snapshot-restore-path-containment/issues/03-fence-layers-not-independently-pinned.md`，本条不修——那是判据的事，不是 CI 的事。

### 还欠着的

仓库在 GitHub 上还不存在（`git remote -v` 里的 `github` 指向一个尚未创建的 `Paprikaiec/cafecli`），所以：

- **没有任何一次 CI 真的跑过**。上面每个数字都是本机 macOS 上跑出来的，ubuntu runner 一次都没验证。
- badge 在仓库建起来之前会显示 404 / `no status`。
- 第一次推上去之后要复核的：ubuntu 上 `skipped = 0` 是否成立（系统 git 有、符号链接有，但没实测过）、`TERM` 钉死是否真的盖住了 runner 的默认值、maven 缓存首次是冷的所以时长不可比。
