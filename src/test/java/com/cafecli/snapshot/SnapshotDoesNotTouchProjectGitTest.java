package com.cafecli.snapshot;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 快照子系统**永不写**用户项目根的 {@code .git}（ADR 0018 / issue 02）。
 *
 * <p>判据必须**读那个文件本身**：「快照跑通了」「仓库能打开」在 gitfile 已被替换时同样为真——
 * 这正是这条 bug 能静默烧掉 5 周工作的原因，任何间接判据对它结构性失明。
 *
 * <p>两种 {@code .git} 形态都在判据里：修复走的是对二者**同一条路径**的实现，
 * 只覆盖一种形态的测试会在半坏的改动上变绿。
 */
class SnapshotDoesNotTouchProjectGitTest {

    @TempDir
    Path tempDir;

    /**
     * 项目根是 linked worktree（{@code .git} 是 gitfile）时，一次 pre-turn 快照后该文件逐字节不变。
     */
    @Test
    void linkedWorktreeGitfileIsNeverTouched() throws Exception {
        assumeSystemGit();
        Path worktree = linkedWorktreeFixture();
        Path gitfile = worktree.resolve(".git");
        byte[] before = Files.readAllBytes(gitfile);

        SideGitManager manager = managerFor(worktree);
        manager.preTurnSnapshot("t1", "fixture");

        assertTrue(Files.isRegularFile(gitfile), ".git 必须仍是 gitfile，不能变成目录");
        assertArrayEquals(before, Files.readAllBytes(gitfile),
                "项目根 .git 被改写了——快照仓库劫持了用户的工作树（ADR 0018）");

        // worktree 仍解析到它自己的历史，而不是快照侧历史
        assertEquals("feat", git(worktree, "rev-parse", "--abbrev-ref", "HEAD").trim(),
                "worktree 的 HEAD 不再是它自己的分支");
        assertTrue(git(worktree, "log", "--oneline").contains("base"),
                "worktree 的历史被替换成了快照历史");

        assertNoWorktreeInConfig(manager);
        assertSnapshotCaptured(manager, "f.txt");
    }

    /**
     * 反例桩的正面：项目根是普通仓库（{@code .git} 是目录）时不回归。
     */
    @Test
    void plainRepositoryGitDirectoryIsNeverTouched() throws Exception {
        assumeSystemGit();
        Path project = tempDir.resolve("plain");
        Files.createDirectories(project);
        git(project, "init", "-q", "-b", "main", ".");
        Files.writeString(project.resolve("f.txt"), "hi\n");
        git(project, "add", ".");
        git(project, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "base");

        Path dotGit = project.resolve(".git");
        assertTrue(Files.isDirectory(dotGit), "fixture 前提：.git 是目录");

        SideGitManager manager = managerFor(project);
        manager.preTurnSnapshot("t1", "fixture");

        assertTrue(Files.isDirectory(dotGit), ".git 必须仍是目录，不能被换成 gitfile");
        assertEquals("main", git(project, "rev-parse", "--abbrev-ref", "HEAD").trim(),
                "项目仓库的 HEAD 被改写了");
        assertTrue(git(project, "log", "--oneline").contains("base"),
                "项目仓库的历史被替换了");

        assertNoWorktreeInConfig(manager);
        assertSnapshotCaptured(manager, "f.txt");
    }

    /**
     * 同一条不变式的另一道判据，形态照 {@code MainInputNormalizationTest} 里那条终端读取守卫：
     * 上面两个用例只覆盖**现有**入口，而这条 bug 的复发形态是**新加**一个用 {@code Git.init()} 或
     * {@code create(false)} 的调用点——那种改动不会让任何行为断言变红。副作用属于「创建一个 gitDir
     * 位于工作树之外的非 bare 仓库」这件事，两个 API 都会写那个 gitfile（ADR 0018 已实测证伪）。
     */
    @Test
    void noSnapshotCodeCreatesANonBareRepositoryOverSomeoneElsesWorkTree() throws IOException {
        Path dir = Path.of("src/main/java/com/cafecli/snapshot");
        assertTrue(Files.isDirectory(dir), "missing source dir: " + dir);
        try (var sources = Files.list(dir)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                // 注释里**必须**能提这两个 API——那几条「不要走这条岔路」的说明正是这条 bug 最值钱的
                // 遗产。所以判据看的是去掉注释之后的代码（本包里没有含 /* 或 // 的字符串字面量）。
                String text = Files.readString(source, java.nio.charset.StandardCharsets.UTF_8)
                        .replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "");
                String name = source.getFileName().toString();
                assertFalse(text.contains("Git.init("),
                        name + ": Git.init() 会往项目根写 gitfile，快照仓库必须 bare 创建（ADR 0018）");
                assertFalse(text.contains("create(false)"),
                        name + ": create(false) 同样写那个 gitfile——副作用不属于 InitCommand（ADR 0018）");
                assertFalse(text.contains("setDirectory("),
                        name + ": setDirectory(projectRoot) 就是 --separate-git-dir 语义（ADR 0018）");
            }
        }
    }

    // --- 判据 ---

    /** 绑定是调用的属性，不是仓库的属性：{@code core.worktree} 不得落进快照仓库 config。 */
    private void assertNoWorktreeInConfig(SideGitManager manager) throws IOException {
        String config = Files.readString(manager.gitDir().resolve("config"));
        assertFalse(config.contains("worktree"),
                "快照仓库 config 持久化了 core.worktree:\n" + config);
    }

    /**
     * 反例桩：没有这一条，一个什么都不做的 {@code openGit()} 也能让上面的断言全绿。
     * 直接读快照提交的 tree，而不是问「快照有没有成功」。
     */
    private void assertSnapshotCaptured(SideGitManager manager, String expectedPath) throws Exception {
        List<TurnSnapshot> snapshots = manager.listSnapshots(5);
        assertEquals(1, snapshots.size(), "应有且只有一个 pre-turn 快照");
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(manager.gitDir().toFile())
                .build()) {
            List<String> paths = new ArrayList<>();
            ObjectId head = ObjectId.fromString(snapshots.get(0).commitId());
            try (RevWalk walk = new RevWalk(repo); TreeWalk treeWalk = new TreeWalk(repo)) {
                RevCommit commit = walk.parseCommit(head);
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    paths.add(treeWalk.getPathString());
                }
            }
            assertTrue(paths.contains(expectedPath),
                    "快照没有捕获到工作树内容，捕获到的是: " + paths);
            assertFalse(paths.stream().anyMatch(p -> p.equals(".git") || p.startsWith(".git/")),
                    "快照把项目的 .git 也提交进去了: " + paths);
        }
    }

    // --- fixture ---

    /**
     * 造一个真实的 linked worktree。JGit 不需要能造它——测试环境依赖系统 git 可以接受，
     * 生产代码不依赖才是要求（第 18 期选 JGit 纯 Java 是有意的）。
     */
    private Path linkedWorktreeFixture() throws Exception {
        Path main = tempDir.resolve("mr");
        Files.createDirectories(main);
        git(main, "init", "-q", "-b", "main", ".");
        git(main, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "base");
        Path worktree = tempDir.resolve("wt");
        git(main, "worktree", "add", "-q", worktree.toString(), "-b", "feat");
        Files.writeString(worktree.resolve("f.txt"), "hi\n");
        assertTrue(Files.isRegularFile(worktree.resolve(".git")),
                "fixture 前提：linked worktree 的 .git 是 gitfile");
        return worktree;
    }

    private SideGitManager managerFor(Path projectRoot) {
        return new SideGitManager(projectRoot,
                new SnapshotConfig(true, tempDir.resolve("snapshots"), 50, List.of(".git", "target")));
    }

    /** 没有系统 git 时 skip，不是 pass。 */
    private void assumeSystemGit() {
        boolean available;
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            available = process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            available = false;
        }
        assumeTrue(available, "需要系统 git 造 worktree fixture");
    }

    private String git(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "git 超时: " + command);
        assertEquals(0, process.exitValue(), "git 失败: " + command + "\n" + output);
        return output;
    }
}
