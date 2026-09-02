# 01 — 快照仓库 bare 创建 + 按调用绑定工作树

Status: ready-for-agent
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

- [ ] 项目根 `.git` 是 **gitfile**（linked worktree）时，跑一次 `preTurnSnapshot`，`.git` 内容逐字节不变
- [ ] 同上，`git -C <worktree> rev-parse --abbrev-ref HEAD` 仍返回它自己的分支，`git log` 仍是它自己的历史
- [ ] 项目根 `.git` 是**目录**（普通仓库）时行为不回归：快照照常创建、`.git` 目录不被动
- [ ] 快照仓库确实捕获到了工作树内容（`git --git-dir=<snap> --work-tree=<proj> log --stat` 能看到文件）
- [ ] 快照仓库 config 里**没有** `core.worktree`
- [ ] `/snapshot`、`/restore <N>`、`revert_turn` 三条路径在两种形态下都跑通

## 不要走的岔路

`Git.init()` 换成 `FileRepositoryBuilder` + `create(false)` **不修复问题**——已实测证伪：`create(false)` 在 gitDir 位于工作树之外时同样写 gitfile。副作用属于「创建一个 gitDir 在工作树之外的**非 bare** 仓库」这件事，不属于 `InitCommand`。必须 bare 创建。

## 已实测

ADR 0018 的决策 1+2 已在干净临时 worktree 上验证：项目 gitfile 逐字节不变、worktree 仍在 `feat` 分支、快照捕获到 `f.txt`、`core.worktree` 未落 config。本 issue 是把那段验证过的形状搬进 `SideGitManager`。
