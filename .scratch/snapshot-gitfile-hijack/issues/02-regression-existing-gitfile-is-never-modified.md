# 02 — 回归：已存在的 `.git` gitfile 逐字节不变

Status: **done**（2026-09-02，`src/test/java/com/cafecli/snapshot/SnapshotDoesNotTouchProjectGitTest.java`）
Type: 回归测试
Blocked by: 01

## Parent

`.scratch/snapshot-gitfile-hijack/PRD.md` · ADR 0018

## 为什么需要单独一条

「不写用户项目 `.git`」这句话在 `AGENTS.md` 与 `README.md` 里已经写了一期，**它是声称，不是断言**——写下来并没有阻止 bug 发生，也没有在 5 周里报警一次。这条 issue 的价值是把它从纪律变成会红的东西。

判据必须**读那个文件本身**。「快照跑通了」「`/snapshot` 有输出」都为真的同时 gitfile 已经被换掉了——这正是它能静默烧掉 5 周的原因，所以任何间接判据对这条 bug 结构性失明。

## 测试形状

新建 `src/test/java/com/cafecli/snapshot/SnapshotDoesNotTouchProjectGitTest.java`。

**Fixture**：在 `@TempDir` 里造一个真实的 linked worktree。JGit 不需要能造它——用 `execute_command` 级别的 `git worktree add` 造 fixture 是可以的（测试环境依赖系统 git 是可接受的；生产代码不依赖才是要求）。若 CI 上没有 git，测试应 **skip 而不是 pass**。

```
mr/                 主仓库，一条 empty commit
mr/../wt            linked worktree，分支 feat，含一个文件
snapshots/          SnapshotConfig.snapshotsRoot 指到这里
```

**断言**（按重要性排序）：

1. `Files.readAllBytes(wt/".git")` 在 `preTurnSnapshot` 前后**逐字节相等**——直接比字节，不要比「是否还是 gitfile」或「是否还包含 gitdir:」
2. `wt/.git` 的类型不变（是文件就还是文件）
3. 快照仓库 config 不含 `core.worktree`
4. 快照仓库确实拿到了工作树内容（否则第 1 条可以靠「什么都不做」通过）

第 4 条是这组断言的**反例桩**：没有它，一个直接 `return null` 的 `openGit()` 也能让 1–3 全绿。

## 同时覆盖普通仓库

同一个测试类里加一个 `.git` 是**目录**的用例，断言目录仍是目录且仓库可打开。理由：修复走的是「bare + 按调用绑定」这条对两种形态**同一条路径**的实现，所以两种形态都要在判据里，否则回归时只有一半会红。

## 判据

- [x] 测试在**修复前**跑会红（先写测试后改代码，两个用例都红——且红在两个不同的断言上：worktree 形态红在 gitfile 字节，普通仓库形态红在 `core.worktree`）
- [x] 修复后绿
- [x] 无系统 git 时 skip，不是 pass（`assumeTrue`）
- [x] 进 `-Pquick`（`quick` 只按文件名排除几个慢测试，新测试默认就在里面；`mvn test -Pquick` = 1274 tests / 6 failures，与 `142a7a1` 的既有 6 条一致）

## 落地时多出来的一条

普通仓库形态的用例在**修复前**红在 `core.worktree` 上，而不是 gitfile 上——ADR 0018 提出时以为 `core.worktree` 只在 gitfile 形态下被写。「两种形态都要在判据里」这条建议因此比写的时候以为的更值：它抓到的不是同一个 bug 的另一半，是一个没被记录的事实。
