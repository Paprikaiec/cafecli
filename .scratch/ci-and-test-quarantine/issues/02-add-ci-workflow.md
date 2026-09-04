# 02 — 加 CI workflow

Status: ready-for-agent
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

- [ ] 两个平台都绿，且 skipped = 0
- [ ] 故意改坏 `SideGitManager` 的围栏 → CI 变红（门是活的，不是装饰）
- [ ] `README.md` 顶部加 CI badge
