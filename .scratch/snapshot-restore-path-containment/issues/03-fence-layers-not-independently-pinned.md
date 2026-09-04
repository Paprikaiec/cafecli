# 03 — 恢复围栏的三层里，只有一层被测试钉住

Status: ready-for-agent
Type: 测试覆盖（安全）
起因: 执行 `.scratch/ci-and-test-quarantine/issues/02` 的判据「故意改坏 `SideGitManager` 的围栏 → CI 变红」时做的变异测试

## Parent

`.scratch/snapshot-restore-path-containment/PRD.md`

## 结论先说

`SnapshotRestoreStaysInsideProjectRootTest` **没有钉住路径围栏本身**。把 `RestoreFence.contained()` 整个删掉（改成无条件放行），全套 30 条快照 / 围栏测试**依然全绿**。

这不是活体漏洞——现在的代码里另外两层还在，六条逃逸向量仍然是关死的。这是一条**判据覆盖缺口**：一次「顺手简化一下这个 `startsWith` 判断」的重构会带着绿灯合进去。

## 实测（2026-09-04，逐个单层变异，其余两层保持原样）

命令：`mvn -B test -DskipTests=false -Dtest=HijackedProjectGitTest,SideGitManagerTest,SnapshotRestoreStaysInsideProjectRootTest,SnapshotDoesNotTouchProjectGitTest,PathGuardTest`（30 条）

| 变异 | 位置 | 结果 |
|---|---|---|
| A：`contained()` 无条件返回 `Optional.of(candidate)`（围栏形同不存在） | `SideGitManager.java` RestoreFence | **30 绿** |
| B：删掉 file mode 分支（`FileMode.SYMLINK` 那支不走，软链条目当普通字节写出） | `writeTargetTree` | **1 红**：`restoreRebuildsASymlinkEntryAsALinkNotAsAFileHoldingTheTargetPath` |
| C：删掉末段 `Files.isSymbolicLink(file)` 跳过 | `writeTargetTree` | **30 绿** |

另外单独确认过：把 `contain(String)` 里的 `guard.resolveSafe(parent)` 换回 `root().resolve(parent).normalize()`（即修复前那条词法路径）——**6 条全绿**。

## 为什么会这样（别把它读成「测试写错了」）

issue 02 记的「修复前 4 红」是**真的**，只是那次是把 `SideGitManager.java` 整个 stash 掉跑的——三层一起没了。单层变异才能分辨是哪一层在挡。分辨下来：

- 用例 1 / 5（`linked.env` 指向根外）在快照里的 mode 就是 120000，走的是 `restoreSymlink`，被 **B 层**挡住；把 A 层拿掉之后 `restoreSymlink` 只是把同一条链接原样重建，根外文件字节不变，所以判据仍绿。
- 用例 2（`assets` 软链目录）本以为是 A 层的活体，实际上恢复前的删除遍历先把 `assets` 这条链接删掉了，写入落在真目录里，也到不了 A 层。
- **C 层在这个测试类里是不可达代码**：没有任何一条用例造出「目标树是普通文件、当前树末段是软链」这个组合。

## 要做什么

给 A 层和 C 层各补一条能红的用例，判据仍然是**读根外那个文件的字节**（不是返回值、不是 `restoredFiles`）。至少要覆盖：

- **A 层**：一条目标树里**不存在于当前工作树**的软链条目，其目标指向根外（当前树里没有同名链接可供「原样重建」，所以放行就是在项目内亲手造一个通往根外的入口）。断言恢复后项目内不存在这条越界链接。
- **C 层**：目标树里 `x` 是普通文件，当前工作树里 `x` 是指向根外文件的软链。断言根外文件逐字节不变。

补完之后**必须再跑一遍上面这张变异表**，A / B / C 三行都要有红——这条 issue 的价值就在那张表，不在用例数量。

## 判据

- [ ] 变异 A → 有测试红
- [ ] 变异 C → 有测试红
- [ ] 变异 B → 仍然红（别在补新用例时把它挤掉）
- [ ] 不改产品代码；三层的行为一个都不动

## 注意

**不要把三层合并成一层**「因为反正另外两层挡住了」。反过来才对：三层各挡一类形态（路径落点 / 条目类型 / 末段类型），合并任何两层都会打开一类。缺的是判据，不是层。
