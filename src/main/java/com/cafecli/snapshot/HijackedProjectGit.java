package com.cafecli.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 一个**已经被咬过**的项目：它根目录的 {@code .git} 是 gitfile，而且指向 Cafecli 的快照仓库。
 *
 * <p>ADR 0018 修的是这条 bug 不再**发生**；对已经发生的实例它无效——那些目录的 {@code .git} 已经
 * 指向 {@code ~/.cafecli/snapshots/…}，修好的 Cafecli 在里面跑，仍然会把快照仓库当成项目仓库。
 *
 * <p>命中之后的动作是**停用快照 + 打印指引**，不自动改写（ADR 0018 决策 4）：从
 * {@code gitdir: <snapshots>/…} 这个指向推不回原始 gitdir——{@code git worktree repair} 必须从主仓库
 * 那侧发起，被劫持的这侧没有任何信息可以反推。猜错就是第二次静默改写，而这次发生在用户已经知道
 * 有问题的时候，比第一次更糟。
 *
 * <p>本类**只读**。它是这条 bug 的补救，不能是同一类错误的第二次。
 */
public record HijackedProjectGit(Path gitfile, Path snapshotGitDir) {

    private static final String GITDIR_PREFIX = "gitdir:";
    /** gitfile 是一行路径；比这大的一定不是 gitfile，不值得读进内存。 */
    private static final long MAX_GITFILE_BYTES = 4096;

    /**
     * 除了配置里的快照根，还查两个历史默认位置：被咬的用户是被**旧版本**咬的，那时的快照根可能落在
     * paicli-era 的 {@code ~/.paicli/snapshots}，而今天的配置已经解析到 {@code ~/.cafecli/snapshots}。
     * 漏检的代价是继续静默丢数据；多查两个目录没有误报风险——没人会把真的 worktree gitdir 放在那下面。
     */
    public static Optional<HijackedProjectGit> detect(Path projectRoot, Path configuredSnapshotsRoot) {
        Path home = Path.of(System.getProperty("user.home", ""));
        return detectUnder(projectRoot, List.of(
                configuredSnapshotsRoot,
                home.resolve(".cafecli").resolve("snapshots"),
                home.resolve(".paicli").resolve("snapshots")));
    }

    static Optional<HijackedProjectGit> detectUnder(Path projectRoot, Collection<Path> snapshotRoots) {
        if (projectRoot == null || snapshotRoots == null) {
            return Optional.empty();
        }
        Path dotGit = projectRoot.resolve(".git");
        Path target = readGitdirTarget(projectRoot, dotGit);
        if (target == null) {
            return Optional.empty();
        }
        for (Path root : snapshotRoots) {
            if (root != null && isUnder(target, root)) {
                return Optional.of(new HijackedProjectGit(dotGit, target));
            }
        }
        return Optional.empty();
    }

    /** {@code .git} 是目录（普通仓库）、不存在、或不是 gitfile 时返回 null。 */
    private static Path readGitdirTarget(Path projectRoot, Path dotGit) {
        try {
            if (!Files.isRegularFile(dotGit) || Files.size(dotGit) > MAX_GITFILE_BYTES) {
                return null;
            }
            String content = Files.readString(dotGit, StandardCharsets.UTF_8);
            String first = content.lines().map(String::trim).filter(line -> !line.isEmpty())
                    .findFirst().orElse("");
            if (!first.startsWith(GITDIR_PREFIX)) {
                return null;
            }
            String raw = first.substring(GITDIR_PREFIX.length()).trim();
            if (raw.isEmpty()) {
                return null;
            }
            // submodule 的 gitfile 用相对路径，按 git 的规则相对 .git 所在目录解析。
            Path target = Path.of(raw);
            return (target.isAbsolute() ? target : projectRoot.resolve(target))
                    .toAbsolutePath().normalize();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean isUnder(Path candidate, Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        if (candidate.startsWith(normalizedRoot)) {
            return true;
        }
        // 快照目录可能已被删掉，realPath 取不到就只认上面那次比较。
        Path realCandidate = realOrNull(candidate);
        Path realRoot = realOrNull(normalizedRoot);
        return realCandidate != null && realRoot != null && realCandidate.startsWith(realRoot);
    }

    private static Path realOrNull(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 给用户的话。修复由用户在**主仓库**那侧发起，不是在被劫持的这个目录里——这句必须说清楚，
     * 否则用户会在错的目录里试 {@code git worktree repair}。
     */
    public String warning() {
        Path projectRoot = gitfile.getParent();
        return """
                ⚠️  快照已停用：这个目录的 .git 指向 Cafecli 的快照仓库
                   %s
                     → %s

                   这是 Cafecli 旧版本的缺陷（ADR 0018）：它覆盖了 linked worktree / submodule 的 .git，
                   此后这个目录下的所有 git 操作都解析到快照仓库、而不是你的项目仓库。
                   快照已停用以免继续写入。Cafecli **不会**自动改写你的 .git——那需要主仓库那侧的信息。

                   修复（在**主仓库**那侧发起，不是在这个目录里）：
                     cd <主仓库>
                     git worktree repair %s
                     git worktree list          # 两个路径都应解析

                   如果你在这个目录里的提交「不见了」，它们大概率在快照侧历史里：
                     git --git-dir=%s log --oneline
                """.formatted(gitfile, snapshotGitDir,
                projectRoot == null ? "<这个目录>" : projectRoot, snapshotGitDir);
    }
}
