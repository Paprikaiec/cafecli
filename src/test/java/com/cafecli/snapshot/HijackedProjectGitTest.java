package com.cafecli.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检测「已经被咬过」的项目（ADR 0018 决策 4 / issue 03 选项 A）。
 *
 * <p>两组判据同等重要：**命中**（否则用户带着坏 {@code .git} 继续工作），以及**不误报**
 * （误报会把快照从正常的 worktree / submodule 项目上关掉）。外加一条：检测**不写任何东西**。
 */
class HijackedProjectGitTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsGitfilePointingIntoTheSnapshotRoot() throws Exception {
        Path snapshots = tempDir.resolve("snapshots");
        Path snapshotGitDir = snapshots.resolve("08f743c2").resolve("a61574ae").resolve(".git");
        Files.createDirectories(snapshotGitDir);
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".git"), "gitdir: " + snapshotGitDir + "\n");

        Optional<HijackedProjectGit> hit = HijackedProjectGit.detectUnder(project, List.of(snapshots));

        assertTrue(hit.isPresent(), "指向快照根之下的 gitfile 必须命中");
        assertEquals(snapshotGitDir, hit.get().snapshotGitDir());
        assertTrue(hit.get().warning().contains("git worktree repair"),
                "指引必须给出主仓库那侧的修复命令");
    }

    @Test
    void plainRepositoryDirectoryIsNotAHit() throws Exception {
        Path project = tempDir.resolve("plain");
        Files.createDirectories(project.resolve(".git"));

        assertFalse(HijackedProjectGit.detectUnder(project, List.of(tempDir.resolve("snapshots"))).isPresent(),
                ".git 是目录（普通仓库）不该命中");
    }

    @Test
    void healthyLinkedWorktreeGitfileIsNotAHit() throws Exception {
        Path project = tempDir.resolve("wt");
        Files.createDirectories(project);
        Files.writeString(project.resolve(".git"),
                "gitdir: " + tempDir.resolve("mr").resolve(".git").resolve("worktrees").resolve("wt") + "\n");

        assertFalse(HijackedProjectGit.detectUnder(project, List.of(tempDir.resolve("snapshots"))).isPresent(),
                "指向主仓库的正常 worktree gitfile 不该命中");
    }

    @Test
    void relativeSubmoduleGitfileIsNotAHit() throws Exception {
        Path project = tempDir.resolve("super").resolve("sub");
        Files.createDirectories(project);
        // submodule 的 gitfile 是相对路径，必须相对 .git 所在目录解析后再判断
        Files.writeString(project.resolve(".git"), "gitdir: ../.git/modules/sub\n");

        assertFalse(HijackedProjectGit.detectUnder(project, List.of(tempDir.resolve("snapshots"))).isPresent(),
                "普通 submodule 的相对 gitfile 不该命中");
    }

    @Test
    void missingOrGarbageGitIsNotAHit() throws Exception {
        Path empty = tempDir.resolve("no-git");
        Files.createDirectories(empty);
        assertFalse(HijackedProjectGit.detectUnder(empty, List.of(tempDir.resolve("snapshots"))).isPresent(),
                "没有 .git 不该命中");

        Path garbage = tempDir.resolve("garbage");
        Files.createDirectories(garbage);
        Files.writeString(garbage.resolve(".git"), "not a gitfile at all\n");
        assertFalse(HijackedProjectGit.detectUnder(garbage, List.of(tempDir.resolve("snapshots"))).isPresent(),
                "不是 gitfile 的普通文件不该命中");
    }

    /**
     * 被劫持的项目上：快照停用、不写快照仓库、**也不碰那个已经坏了的 gitfile**。
     * 检测是这条 bug 的补救，不能是同一类错误的第二次。
     */
    @Test
    void hijackedProjectDisablesSnapshotsAndWritesNothing() throws Exception {
        Path snapshots = tempDir.resolve("snapshots");
        Path snapshotGitDir = snapshots.resolve("08f743c2").resolve("a61574ae").resolve(".git");
        Files.createDirectories(snapshotGitDir);
        Path project = tempDir.resolve("bitten");
        Files.createDirectories(project);
        Path gitfile = project.resolve(".git");
        Files.writeString(gitfile, "gitdir: " + snapshotGitDir + "\n");
        Files.writeString(project.resolve("a.txt"), "work\n");
        byte[] gitfileBefore = Files.readAllBytes(gitfile);
        List<String> treeBefore = listTree(tempDir);

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target")));

        assertTrue(manager.hijackedProjectGit().isPresent(), "应识别为已被劫持");
        assertFalse(manager.config().enabled(), "被劫持时快照必须停用");
        assertNull(manager.preTurnSnapshot("t1", "should be a no-op"), "停用后不该产生快照");
        assertFalse(manager.restorePreTurn(1).success(), "停用后不该能恢复");
        assertTrue(manager.formatStatus().contains("git worktree repair"),
                "/snapshot 应该解释为什么关着，而不是只说「关闭」");

        assertArrayEqualsBytes(gitfileBefore, Files.readAllBytes(gitfile));
        assertEquals(treeBefore, listTree(tempDir), "检测路径写了文件——它必须是只读的");
    }

    private static void assertArrayEqualsBytes(byte[] expected, byte[] actual) {
        assertEquals(new String(expected), new String(actual), "被劫持的 gitfile 被改动了");
    }

    private static List<String> listTree(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            List<String> paths = new ArrayList<>(walk.map(root::relativize).map(Path::toString).toList());
            paths.sort(String::compareTo);
            return paths;
        }
    }
}
