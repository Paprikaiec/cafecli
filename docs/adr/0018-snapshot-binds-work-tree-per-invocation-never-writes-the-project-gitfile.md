# 快照仓库按调用绑定工作树，永不写用户项目的 `.git`

> 状态：**accepted**（2026-09-02 提出并于同日确认落地）。第 18 期 side-history 快照当初没有 ADR，本文补上它缺的那条决策：它的实现违反了 `AGENTS.md` / `README.md` 里已经写下的不变式「快照仓库位于 `~/.cafecli/snapshots/<project_hash>/<worktree_hash>/.git`，**不写用户项目 `.git`**」。

第 18 期的 side-history 快照声称只在 `~/.cafecli/snapshots/` 下活动。实测它会**覆盖用户项目根目录的 `.git`**——当那个 `.git` 是一个 gitfile（即项目根本身是一个 `git worktree`，或是一个 submodule）时。后果不是报错而是**静默改写历史归属**：此后该目录下的所有 git 操作都解析到快照仓库，用户以为自己在项目仓库里提交，实际提交进了快照侧历史。

## 证据

本仓库自己被这条 bug 咬过一次：`/Users/wyw/Project/paicli-tui-rendering` 是 `paicli` 的一个 linked worktree，其 `.git` 被改写成指向 `~/.cafecli/snapshots/08f743c21f41b89c/a61574aec0b1df78/.git`，**2026-08-01 → 2026-09-01 约 5 周的渲染器工作因此全部提交进了快照侧历史而不是项目仓库**（后经 `0160d0e` 单次 import 补回）。

2026-09-02 在干净临时目录上确定性复现（首次即命中，非偶发）：

| 项目根的 `.git` 形态 | 结果 |
|---|---|
| **gitfile**（linked worktree / submodule） | **被覆盖**。`.git` 内容由 `gitdir: <main>/.git/worktrees/wt` 变成 `gitdir: <snapshots>/…/.git`；快照仓库 config 被写入 `core.worktree = <项目根>` |
| **目录**（普通仓库） | `.git` 不受影响，仓库完好；但快照仓库 config **同样**被写入 `core.worktree = <项目根>` |

（第二行的后半句是落地时由回归测试查出来的，提出本 ADR 时以为 `core.worktree` 只在 gitfile 形态下出现。它本身不造成劫持——非 bare 创建那一步没有 gitfile 可覆盖——但它意味着**任何**直接对着这个 gitDir 跑的 git 操作都会写进用户项目，所以决策 2 的「不落 config」对两种形态一样必要。）

被覆盖之后从该目录内 `git log` 看到的是 `pre-turn t1`、`git rev-parse --abbrev-ref HEAD` 报 `main`——用户真实的 `feat` 分支与其提交从这个视图里**完全消失**；而主仓库那边 `git worktree list` **仍然把它列为已注册的 worktree**。两个视图互不知情，这正是它能静默烧掉 5 周的原因。

## 根因

`SideGitManager.openGit()`（`src/main/java/com/cafecli/snapshot/SideGitManager.java:177-192`）：

```java
Git.init().setGitDir(gitDir.toFile()).setDirectory(projectRoot.toFile()).call();
```

`gitDir` 与 `directory` 不同即 `git init --separate-git-dir` 语义：JGit 按约定往 `directory` 写一个指回 `gitDir` 的 gitfile。**这是 JGit 的正确行为，错的是我们在别人的工作树上要求了这个语义。**

关键点是它**不是 `Git.init()` 独有的**：实测 `Repository.create(false)`（经 `FileRepositoryBuilder` 带 `setWorkTree` 构造）同样写这个 gitfile。因此「把 `Git.init` 换成 `FileRepositoryBuilder` + `create(false)`」这个看起来最自然的最小改法**不修复问题**——已实测证伪，不要再走一遍。

## 决策

1. **创建与绑定分离。** 快照仓库以 **bare** 形态在 `gitDir` 处创建（`create(true)`），创建时**根本不告诉 JGit 有工作树**，因此没有任何东西可以被写进用户项目；随后把该仓库 config 的 `core.bare` 置为 `false`，使其可以在后续调用里接受一个外部工作树。
2. **工作树按调用绑定，永不持久化。** 每次快照 / 恢复操作各自用 `FileRepositoryBuilder().setGitDir(gitDir).setWorkTree(projectRoot).build()` 建立绑定，等价于每次显式传 `--git-dir` / `--work-tree`。`core.worktree` **不写进 config**——绑定是调用的属性，不是仓库的属性。
3. **守门优先于修复。** 快照子系统在任何写操作前断言：项目根的 `.git` 若已存在，其 mtime 与内容在整个操作前后**必须逐字节不变**。这条断言由回归测试钉住（见 issue 02），不依赖「我们记得不要那样做」。
4. **已被咬过的用户不自动修。** 检测到项目根 `.git` 是 gitfile 且指向 `~/.cafecli/snapshots/` 时，**停用快照并给出可读警告 + 手工修复指引**（`git worktree repair`），不静默改写。理由见「被否决的方案」。

实测验证（同一临时 worktree、同一复现脚本）：决策 1+2 之后项目 gitfile 逐字节不变、worktree 仍在 `feat` 分支上、快照仓库照常捕获到工作树内容、`core.worktree` 未落 config。

## 落地时补上的三条

提出与实现之间只差几小时，但实现查出了提出时没想到的东西，记在这里免得下次重新发现：

1. **旧仓库要就地清理 `core.worktree`。** 决策 2 只说「不写」，但 bug 期创建的快照仓库 config 里已经留着一条 `core.worktree = <用户项目根>`。显式绑定虽然覆盖它，但只要它还在，任何直接对着那个 gitDir 跑的 git 命令都会写进用户项目。`openGit()` 因此在绑定后检查一次并 unset（只写快照仓库自己的 config，不碰用户项目）。
2. **bare 创建会顺带关掉 reflog。** `create(true)` 的默认 `core.logallrefupdates` 是 false，非 bare 是 true。这是本决策引入的行为差异，已显式置回 true——快照仓库的 reflog 正是一次坏恢复之后把东西找回来的东西。
3. **决策 4 的落点是 `HijackedProjectGit`**（只读检测 + 停用 + 指引，选项 A）。检测除配置里的快照根外，还查 `~/.cafecli/snapshots` 与 `~/.paicli/snapshots` 两个历史默认位置：被咬的用户是被**旧版本**咬的，那时的快照根可能落在 paicli-era 的路径上。漏检的代价是继续静默丢数据；多查两个目录没有误报风险。

## 被否决的方案

- **`Git.init()` 换成 `FileRepositoryBuilder` + `create(false)`**：否决——**已实测证伪**，`create(false)` 一样写 gitfile。这是本 ADR 最值得记下来的一条：直觉上「不用 InitCommand 就不会有 init 的副作用」是错的，副作用属于「创建一个 gitDir 在工作树之外的非 bare 仓库」这件事本身。
- **检测到是 worktree 就整体停用快照**：否决——linked worktree 是完全正常的开发形态，在其中失去快照能力是把 bug 变成功能缺失。bare + 按调用绑定对 worktree 与普通仓库是同一条路径，没有分叉，因此也没有「只在某一种形态下被测到」的风险。
- **自动修复已被劫持的 gitfile**：否决——我们无法从 `~/.cafecli/snapshots/…` 这个指向反推原始 gitdir（`git worktree repair` 需要从主仓库那侧发起）。猜错就是第二次静默改写，而这次是在用户已经知道有问题的时候。停用 + 指引把决定权交回用户。
- **把快照仓库改成 bare + 每次 `git --work-tree` 走子进程**：否决——引入对系统 git 的运行时依赖，而第 18 期选 JGit 纯 Java 正是为了避免它。

## 后果

- `AGENTS.md` / `README.md` 里「不写用户项目 `.git`」从**声称**变成**被回归测试钉住的断言**（`SnapshotDoesNotTouchProjectGitTest`，两种 `.git` 形态都在判据里；判据直接比 gitfile 字节，因为「快照跑通了」在 gitfile 已被换掉时同样为真）。
- `CONTEXT.md` 已补上「快照绑定（Snapshot binding）」与「被劫持的项目（Hijacked project）」两个词条：gitDir 是仓库的属性、工作树是调用的属性，两者不得互相持久化。
- 任何未来在快照包里使用 `Git.init()` / `Repository.create(false)` 的改动都要重新过这条判据——它们的默认行为与本决策相反。
- 落地拆解见 `.scratch/snapshot-gitfile-hijack/`。
