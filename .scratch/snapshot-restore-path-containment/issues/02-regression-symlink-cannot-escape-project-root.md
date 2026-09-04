# 02 — 回归：软链不得把写 / 删带出项目根

Status: done（2026-09-02）
Type: 回归测试（安全）
Blocked by: 01

## Parent

`.scratch/snapshot-restore-path-containment/PRD.md`

## 为什么单独一条

围栏的判据必须**看根外那个文件**，不能看返回值。已实测：越界发生时 `RestoreResult.success = true`、`restoredFiles` 里只有项目内的相对路径、屏幕上没有任何异常——**所有间接判据都是绿的**。这与 ADR 0018 那条 bug 是同一种失明。

## 测试形状

新建 `src/test/java/com/cafecli/snapshot/SnapshotRestoreStaysInsideProjectRootTest.java`。

Fixture 用 `@TempDir`，布局：

```
outside/secret.txt          根外的受害者文件，已知内容
outside/dir/                根外的受害者目录
proj/                       项目根
proj/normal.txt             普通文件，用作反例桩
proj/linked.txt  -> outside/secret.txt        （软链指向根外文件）
proj/assets      -> outside/dir               （软链指向根外目录）
```

用例：

1. **写穿单个文件**：快照 → 改动 → 恢复。断言 `outside/secret.txt` **逐字节不变**。
2. **穿过软链目录写**：快照里含 `assets/planted.txt` → 恢复。断言 `outside/dir` 下**没有新增任何文件**（比对恢复前后的整棵文件清单，不是只查 `planted.txt`）。
3. **删除侧**：让 `assets/x` 存在于当前树、不在目标树 → 恢复。断言根外文件未被删除。
4. **符号链接条目形态**：恢复后 `proj/linked.txt` **仍是符号链接**（`Files.isSymbolicLink`），且其内容不等于链接目标字符串。
5. **反例桩**：`proj/normal.txt` 确实被恢复。没有这一条，一个直接 `return` 的 `writeTargetTree` 能让 1–4 全绿。

## 判据

- [x] 测试在**修复前**跑会红（先确认有牙，再合 01）；用例 1 与用例 4 应各自红在不同断言上
- [x] 修复后全绿
- [x] 普通项目的恢复行为不回归（用例 5）
- [x] 进 `-Pquick`——它快，且这条的代价是根外数据销毁

## 结果（2026-09-02）

`SnapshotRestoreStaysInsideProjectRootTest`，6 条。**修复前 4 红 2 绿，各红在不同断言上**（把 `SideGitManager.java` 单独 stash 掉跑的，测试没动）：

| 用例 | 修复前 |
|---|---|
| `restoreDoesNotWriteThroughASymlinkToAFileOutsideTheRoot` | 红：根外文件 19 字节 → 93 字节（被链接目标路径字符串覆盖） |
| `restoreDoesNotWriteThroughASymlinkToADirectoryOutsideTheRoot` | 红：根外目录多出 `planted.txt`；`kept.txt` 未被删 |
| `restoreRebuildsASymlinkEntryAsALinkNotAsAFileHoldingTheTargetPath` | 红：`isSymbolicLink` false——软链条目被写成了普通文件 |
| `restoreLeavesAnEscapingSymlinkAsALink` | 红：链接目标的内容变了 |
| `restoreDeletesTheSymlinkItselfNotItsTargetOutsideTheRoot` | **绿**（见下） |
| `restoreStillRewritesOrdinaryFilesInsideTheRoot` | 绿（反例桩，本就该绿） |

修复后 6 绿。`-Pquick` 1281 tests / 6 failures，与基线（1275 / 6，5×`InlineRendererTest` + 1×`TerminalMarkdownRendererTest` 顺序依赖）逐条相同，无回归。

**用例 3（删除侧）修复前就是绿的**，与 issue 01 的预期相反：Java 的 `Files.delete*` 从不跟随末段软链。用例保留——它钉住的是「删的是链接不是目标」这条正确行为，只是它从来没红过，别把它读成这条 bug 的活体。

真机复现（handoff 的 harness，打包后的 `cafecli-1.0.0.jar`）：`outside/prod.env` 仍是 `PROD_SECRET=keepme`，`normal.txt` 恢复成 `hello`，`linked.env` 仍是软链，`restoredFiles=[normal.txt]`。

## 注意

Windows 上创建符号链接需要权限。若在 CI 上不可用，用 `assumeTrue`（**skip 而不是 pass**），与 `SnapshotDoesNotTouchProjectGitTest` 处理系统 git 的方式一致。
