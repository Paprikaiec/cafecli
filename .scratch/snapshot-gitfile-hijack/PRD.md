# PRD: 快照服务劫持 worktree 的 `.git`

Status: ready-for-agent
Date: 2026-09-02
Branch: feat/renderer-import（发现于此；修复建议单开分支）
Decision record: ADR 0018（快照按调用绑定工作树，永不写用户项目 `.git`）
Severity: **数据丢失级**（静默改写历史归属，已实际造成约 5 周工作提交到错误的仓库）

## Problem Statement

第 18 期的 side-history 快照声称「快照仓库位于 `~/.cafecli/snapshots/<project_hash>/<worktree_hash>/.git`，**不写用户项目 `.git`**」——这句话同时写在 `AGENTS.md` 与 `README.md` 里。实际上它会覆盖用户项目根的 `.git`，条件是那个 `.git` 是一个 **gitfile** 而不是目录，也就是项目根本身是一个 `git worktree`（或 submodule）。

失败形态不是报错，而是**静默改写历史归属**：`.git` 被改写后，该目录下所有 git 操作都解析到快照仓库。用户在 `git log` 里看到一段看似合理的历史（快照仓库把真实的手写提交与机器生成的 `pre-turn` / `post-turn react-*` 提交交错在一起），于是完全没有理由怀疑。主仓库那边 `git worktree list` 仍把这个目录列为已注册的 worktree，两个视图互不知情。

本仓库自己被它咬过：`paicli-tui-rendering` worktree 的 2026-08-01 → 2026-09-01 约 5 周渲染器工作全部进了快照侧历史，事后靠 `0160d0e` 一次性 import 才补回主仓库。**它会复发**——下一次在任何 worktree 里跑 Cafecli 就会再来一遍。

## Solution

按 ADR 0018：

- 快照仓库以 **bare** 形态创建（创建时不告诉 JGit 有工作树，因此没有东西可以被写进用户项目），随后 `core.bare=false` 使其可接受外部工作树
- 工作树**按调用绑定**（`FileRepositoryBuilder.setGitDir(...).setWorkTree(...)`），`core.worktree` 不落 config——绑定是调用的属性，不是仓库的属性
- 「项目根 `.git` 逐字节不变」由回归测试钉住，而不是靠纪律
- 已被劫持的用户：检测 + 停用 + 手工修复指引，**不自动改写**

已在干净临时 worktree 上实测：改法之后项目 gitfile 逐字节不变、worktree 仍解析到自己的分支、快照仍正常捕获工作树内容。

## 复现

确定性复现（首次即命中）：

```bash
R=$(mktemp -d); cd "$R"
git init -q mr && cd mr
git -c user.email=t@t -c user.name=t commit -q --allow-empty -m base
git worktree add -q ../wt -b feat && echo hi > ../wt/f.txt
cat "$R/wt/.git"          # gitdir: <R>/mr/.git/worktrees/wt

# 在 $R/wt 上跑一次 SideGitManager.preTurnSnapshot(...)
cat "$R/wt/.git"          # gitdir: <snapshots>/…/.git      ← 被覆盖
git -C "$R/wt" log --oneline -1   # pre-turn t1             ← 真实历史消失
git -C "$R/mr" worktree list      # 仍把 wt 列为已注册      ← 两个视图不一致
```

对照：项目根 `.git` 是**目录**（普通仓库）时不受影响。

## 根因

`src/main/java/com/cafecli/snapshot/SideGitManager.java:177-192`，`Git.init().setGitDir(gitDir).setDirectory(projectRoot)` 即 `--separate-git-dir` 语义，JGit 按约定往 `directory` 写 gitfile。

**注意**：这不是 `Git.init()` 独有的。实测 `Repository.create(false)`（经带 `setWorkTree` 的 `FileRepositoryBuilder` 构造）同样写 gitfile，所以「换掉 InitCommand」这个最小改法**不修复问题**——已证伪，见 ADR 0018「被否决的方案」。

## Issues

| # | 标题 | Status |
|---|---|---|
| 01 | 快照仓库 bare 创建 + 按调用绑定工作树 | ready-for-agent |
| 02 | 回归：已存在的 `.git` gitfile 逐字节不变 | ready-for-agent |
| 03 | 已被劫持的项目：检测、停用、修复指引 | needs-triage |

## Non-goals

- 不修复已经落在快照侧历史里的提交（那是一次性数据恢复，本仓库已用 import 解决；`git bundle` 归档在 `cafecli-snapshot-history-2026-09-01.bundle`）
- 不改快照的路径布局、保留策略或排除规则
- 不引入对系统 `git` 可执行文件的运行时依赖（第 18 期选 JGit 纯 Java 是有意的）
