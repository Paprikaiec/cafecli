# 01 — 恢复路径改用真实路径围栏 + 按 mode 处理符号链接

Status: done（2026-09-02）
Type: 安全缺陷修复（High）

## Parent

`.scratch/snapshot-restore-path-containment/PRD.md`

## 要改的地方

`src/main/java/com/cafecli/snapshot/SideGitManager.java`，两个方法用的是同一条坏判据：

| 位置 | 现状 |
|---|---|
| `writeTargetTree` :316-317 | `resolve().normalize()` + `startsWith`，然后 :320 `createDirectories`、:325 `Files.write` |
| `deleteTrackedFilesMissingFromTarget` :298-299 | 同一条判据，然后 :302 `Files.deleteIfExists` |
| `pruneEmptyParents` :331 | 同样只用 `startsWith` 走父目录 |

`normalize()` 是纯字符串操作，不解析符号链接；三处 IO 都跟随符号链接。

## 改成

**不要写第二套围栏。** 仓库里已经有一套解决同一问题的实现：`PathGuard.resolveRealPath()`（`PathGuard.java:107`）向上找到最近存在的祖先、`toRealPath()` 解析其中的软链、再把剩余段接回，然后校验 `startsWith`。注意它还处理了 macOS 上 `/var` → `/private/var` 的坑（`PathGuard.java:33` 的注释）——自己重写极可能漏掉这一条。

推荐做法，按优先级：

1. **注入 `PathGuard`**，恢复期每个路径过 `resolveSafe()`。围栏只有一处实现，日后不会漂移。
2. 若注入代价太大，则把 `resolveRealPath` 提到一个共用工具类里，两边共用**同一个方法**，不是同一份逻辑的两个拷贝。

另外两条与围栏无关但同属本条修复：

3. **不写穿已有链接**：`Files.isSymbolicLink(file)`（或 `readAttributes(..., NOFOLLOW_LINKS)`）命中时不得 `Files.write` 到该路径。
4. **按 git file mode 分支**：`treeEntries` 目前只收 `ObjectId`，丢掉了 mode。带上 `FileMode`；`SYMLINK` 条目重建为链接（并校验链接目标解析后仍在根内）或跳过，绝不把 blob 内容当普通文件字节写出——现在写出去的正是链接目标字符串。

## 判据

- [x] 项目内 `linked.env -> <根外文件>`：`restorePreTurn` 后根外文件**逐字节不变**
- [x] 项目内 `assets -> <根外目录>`：恢复 `assets/x` 不在根外产生任何文件
- [x] 符号链接条目恢复后**仍是符号链接**，内容不是链接目标字符串
- [x] 删除侧同样不越界：`deleteTrackedFilesMissingFromTarget` 不删根外文件
- [x] 普通项目（无软链）的恢复行为**逐字节不回归**：`restoredFiles` / `removedFiles` 列表与修复前一致
- [x] macOS `/var` vs `/private/var` 不误判（`@TempDir` 就在 `/var` 下，天然覆盖）

## 落地（2026-09-02）

`SideGitManager.java`，走的是本文推荐的第 1 条（复用 `PathGuard`，不写第二套围栏）：

- 新增私有 `RestoreFence`，判据全部委托 `PathGuard.resolveSafe`。**与 `PathGuard` 的差别只在末段**：写工具解析末段是对的（它判断最终落点），恢复不能——落点就是末段本身，跟着它走就是写穿。所以只把父目录链交给 `resolveSafe`，末段按字面拼回。越界不抛给用户，跳过该条目、其余照常恢复；一切 `RuntimeException` 都 fail closed。
- 围栏**现构现用**（在 `restorePreTurn` 里 new）：构造 `SideGitManager` 时项目根可能还不存在，那时 `PathGuard` 解析出来的根是词法的，缓存下来会让 macOS 上 `/private/var` vs `/var` 永远 `startsWith` 失败——**整个恢复静默变成空操作**。
- `treeEntries` 改收 `TreeEntry(ObjectId, FileMode)`。`SYMLINK` 重建为链接（目标解析后仍在根内才建，否则跳过、不计入 `restoredFiles`）；非 `REGULAR_FILE` / `EXECUTABLE_FILE` 的其余 mode（gitlink 等）跳过——它们的 object 不是文件内容。
- 末段已是软链而目标树里是普通文件时**跳过**，不写穿也不替用户改写布局（PRD non-goal）。
- 删除侧判据改 `NOFOLLOW_LINKS`：指向根外目录的软链是「一条该删的链接」，不是「一个该跳过的目录」。
- `pruneEmptyParents` 的围栏根改用 `fence.root()`（真实路径），否则 macOS 上它一步都不会走。

## 一条实测更正

本文原写「删除侧 `Files.deleteIfExists` 一样跟随软链」——**实测不成立**。Java 的 `Files.delete*` 从不跟随末段软链，删的是链接本身。回归里那条删除侧用例（`restoreDeletesTheSymlinkItselfNotItsTargetOutsideTheRoot`）在修复前就是绿的，修复后仍绿。真正越界的是**写**：`assets -> 根外目录` 时 `planted.txt` 落到了根外（已实测，见 issue 02）。删除侧照样接了围栏——同一条判据、防中段软链、纵深防御——但它不是本条 bug 的活体。

## 不要走的岔路

- **只加 `startsWith` 之后的一次 `toRealPath()`** 不够：目标文件常常**还不存在**（新建文件的恢复），`toRealPath()` 会抛 `NoSuchFileException`。必须走「最近存在祖先」那条路，这正是 `PathGuard.resolveRealPath` 已经写好的。
- **只修 `writeTargetTree`** 不够：删除侧 `Files.deleteIfExists` 一样跟随软链。
