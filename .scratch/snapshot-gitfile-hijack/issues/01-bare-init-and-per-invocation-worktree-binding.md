# 01 — 快照仓库 bare 创建 + 按调用绑定工作树

Status: **done**（2026-09-02，分支 `fix/snapshot-gitfile-hijack`）
Type: 缺陷修复（数据丢失级）

## Parent

`.scratch/snapshot-gitfile-hijack/PRD.md` · ADR 0018

## 要改的地方

`src/main/java/com/cafecli/snapshot/SideGitManager.java` 的 `openGit()`（当前 177-192 行）：

```java
private Git openGit() throws IOException, GitAPIException {
    Files.createDirectories(gitDir.getParent());
    if (!Files.exists(gitDir.resolve("config"))) {
        Git.init()
                .setGitDir(gitDir.toFile())
                .setDirectory(projectRoot.toFile())   // ← 这一行让 JGit 往项目根写 gitfile
                .call()
                .close();
    }
    writeExcludeFile();
    Repository repository = new FileRepositoryBuilder()
            .setGitDir(gitDir.toFile())
            .setWorkTree(projectRoot.toFile())        // ← 这一半已经是对的
            .build();
    return new Git(repository);
}
```

第二半（按调用绑定）已经是正确形态，**只有创建那一半要换**。

## 改成

创建期不让 JGit 知道有工作树：

```java
if (!Files.exists(gitDir.resolve("config"))) {
    Files.createDirectories(gitDir);
    Repository bare = new FileRepositoryBuilder()
            .setGitDir(gitDir.toFile())               // 不设 workTree
            .build();
    bare.create(true);                                 // bare：没有工作树可写
    StoredConfig cfg = bare.getConfig();
    cfg.setBoolean("core", null, "bare", false);       // 之后可接受按调用传入的工作树
    cfg.save();
    bare.close();
}
```

绑定那一半保持不变。`core.worktree` **不要**写进 config——绑定是调用的属性。

## 判据

- [x] 项目根 `.git` 是 **gitfile**（linked worktree）时，跑一次 `preTurnSnapshot`，`.git` 内容逐字节不变
- [x] 同上，`git -C <worktree> rev-parse --abbrev-ref HEAD` 仍返回它自己的分支，`git log` 仍是它自己的历史
- [x] 项目根 `.git` 是**目录**（普通仓库）时行为不回归：快照照常创建、`.git` 目录不被动
- [x] 快照仓库确实捕获到了工作树内容（`git --git-dir=<snap> --work-tree=<proj> log --stat` 能看到文件）
- [x] 快照仓库 config 里**没有** `core.worktree`
- [x] `/snapshot`、`/restore <N>` 在两种形态下都跑通（真机脚本直接打 `SnapshotService.status()` / `restorePreTurn()`，即两个命令的实体）；`revert_turn` 走同一个 `restorePreTurn`，未单独跑真机

## 实现与提出的差异

1. **`core.worktree` 两种形态都会落 config**，不只是 gitfile 形态——回归测试先红在这一条上。ADR 0018 的证据表已更正。
2. **旧仓库要就地 unset `core.worktree`**：bug 期建的快照仓库 config 里留着它。`openGit()` 绑定后检查并清一次（只写快照仓库自己的 config）。
3. **bare 创建默认关掉 reflog**（`core.logallrefupdates` false，非 bare 是 true），已显式置回 true 保持与旧行为一致。
4. 顺手修了一个同一方法里的资源泄漏：`new Git(repository)` 的 `closeRepo` 是 false，`createSnapshot` / `listSnapshots` 里的 `try (Git git = openGit())` 从来没关过 Repository（`restorePreTurn` 关了）。两处补成与 `restorePreTurn` 同款的双资源 try。

## 真机验收（2026-09-02）

用生产默认配置（`SnapshotConfig.fromEnvironment()` + 真实 excludes，`-Dpaicli.snapshot.dir` 指到临时目录）在真实 `git worktree add` 出来的 worktree 上跑完整 `runTurn` → `status` → `restorePreTurn`：

- gitfile 逐字节不变（`cmp` 通过）、仍是普通文件
- `git -C wt rev-parse --abbrev-ref HEAD` = `feat`，`git log` = `base`（自己的历史）
- 快照仓库 config 无 `core.worktree`，`git --git-dir=<snap> --work-tree=<wt> log --stat` 看得到 `f.txt` / `added.txt`
- `/restore 1` 恢复 `f.txt`、删除 `added.txt`，均按预期

## 不要走的岔路

`Git.init()` 换成 `FileRepositoryBuilder` + `create(false)` **不修复问题**——已实测证伪：`create(false)` 在 gitDir 位于工作树之外时同样写 gitfile。副作用属于「创建一个 gitDir 在工作树之外的**非 bare** 仓库」这件事，不属于 `InitCommand`。必须 bare 创建。

## 已实测

ADR 0018 的决策 1+2 已在干净临时 worktree 上验证：项目 gitfile 逐字节不变、worktree 仍在 `feat` 分支、快照捕获到 `f.txt`、`core.worktree` 未落 config。本 issue 是把那段验证过的形状搬进 `SideGitManager`。
