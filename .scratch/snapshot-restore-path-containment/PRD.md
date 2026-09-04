# PRD: 快照恢复的路径围栏是词法的，符号链接可以写到项目根之外

Status: done（2026-09-02 修复并回归）
Date: 2026-09-02
Found by: 公开发布前的安全评审（`/security-review`），2026-09-02
Severity: **High**（项目根之外的任意文件覆写 / 删除；无需攻击者参与即可造成数据破坏）
Related: ADR 0018（快照按调用绑定工作树）——同一个文件、同一类「路径围栏没兜住」的缺陷，但根因不同：0018 是**创建期**往项目写 gitfile，本条是**恢复期**写出工作树

## Problem Statement

`SideGitManager` 的恢复路径用**词法**判据做围栏：

```java
Path file = projectRoot.resolve(path).normalize();   // :316（写）/ :298（删）
if (!file.startsWith(projectRoot)) { continue; }
```

`normalize()` **不解析符号链接**，随后的 `Files.createDirectories(parent)`（:320）、`Files.write(file, ...)`（:325）、`Files.deleteIfExists(file)`（:302）**都跟随**符号链接。于是路径字符串留在项目根内、实际写入落到根外。

这正是 `PathGuard` 存在的理由——它用 `resolveRealPath()`（`PathGuard.java:107`，向上找最近存在的祖先做 `toRealPath()` 再接回剩余段）专门解决「项目内的软链指向外部目录」。**但恢复路径根本不过 `PathGuard`。**

附带一条：`writeTargetTree` 忽略 git file mode。符号链接条目（mode 120000）在 git 里的 blob 内容就是链接目标字符串，恢复时被当成普通文件**写穿**那条链接，而不是重建链接。

入口是模型可达的：`revert_turn` 工具（`ToolRegistry.java:103` 列为危险工具）与 `/restore <N>`（`Main.java:1392`），两者都经 `SnapshotService.restorePreTurn()`（:70）。

## 已实测（2026-09-02，非推断）

**零门槛变体——不需要攻击者做任何事：**

```
项目里有 linked.env -> /outside/prod.env      （很常见的布局：.env -> ~/secrets/.env）
/outside/prod.env 原内容： PROD_SECRET=keepme

跑一次 preTurnSnapshot + restorePreTurn(1)：

RestoreResult[success=true, restoredFiles=[linked.env, normal.txt], removedFiles=[]]
/outside/prod.env 现内容： /var/.../outside/prod.env      ← 被链接目标字符串覆盖，原内容销毁
linked.env 仍是符号链接（没被重建成普通文件，是写穿的）
```

**工具报告成功，用户看不到任何异常。** 任何工作区里有指向根外文件的软链的用户，`revert_turn` / `/restore` 一次就会毁掉那个文件。

**提权变体**（安全评审已复现）：模型写一个位于根内、内容自选的 `assets/authorized_keys`（`PathGuard` 放行）→ 快照捕获 → `assets` 之后变成指向 `~/.ssh` 的软链（可来自 agent 检出的不可信仓库内容、npm postinstall、MCP 文件工具、或一次被批准的 `execute_command`）→ 模型调 `revert_turn`，审批卡只说「将按 Side-Git 快照批量恢复工作区文件」→ 密钥落进 `~/.ssh/authorized_keys`。

## 威胁模型

对手是**模型**与 agent 读到的**不可信内容**，不是本机用户。因此「用户自己能写那个文件」不构成缓解：本条的问题是模型可达的工具在用户批准一个看起来普通的恢复动作时，写到了围栏之外。

## Solution

把恢复路径接到与 `PathGuard` 同一套真实路径判据上，并按 file mode 分支：

1. **真实路径围栏**：解析最近存在祖先的 `toRealPath()` 再接回剩余段，重新校验 `startsWith(projectRoot)`；或者直接注入 `PathGuard` 复用 `resolveSafe()`，避免第二套实现漂移。
2. **不写穿已有链接**：以 `LinkOption.NOFOLLOW_LINKS` 判定目标，命中符号链接时不得直接 `Files.write`。
3. **按 mode 处理**：`FileMode.SYMLINK` 条目应重建为链接（且校验链接目标仍在根内）或跳过，绝不当普通文件写字节。
4. 同样处理 `deleteTrackedFilesMissingFromTarget`（:291）与 `pruneEmptyParents`（:331）——删除同样跟随链接。

## Issues

| # | 标题 | Status |
|---|---|---|
| 01 | 恢复路径改用真实路径围栏 + 按 mode 处理符号链接 | done |
| 02 | 回归：软链不得把写 / 删带出项目根 | done |

落地摘要与一条实测更正（删除侧其实没有越界）见 issue 01「落地」/「一条实测更正」，回归数据见 issue 02「结果」。

## Non-goals

- 不改快照的捕获侧（`isExcluded` / 排除规则不动）
- 不引入对系统 `git` 的运行时依赖
- 不试图「修好」用户工作区里已有的软链——围栏的职责是不越界，不是改写用户布局
