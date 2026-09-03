package com.cafecli.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 恢复期的路径围栏必须是**真实路径**判据：项目内的符号链接不得把写 / 删带出项目根。
 *
 * <p>判据必须**读根外那个受害文件的字节**。已实测：越界发生时 {@code RestoreResult.success = true}、
 * {@code restoredFiles} 里只有项目内的相对路径、屏幕上没有任何异常——所有间接判据都是绿的。
 * 这与 ADR 0018 那条 bug 是同一种失明。
 */
class SnapshotRestoreStaysInsideProjectRootTest {

    @TempDir
    Path tempDir;

    /**
     * 零门槛变体，不需要攻击者做任何事：工作区里有 {@code linked.env -> <根外文件>}
     * （很常见的布局，例如 {@code .env -> ~/secrets/.env}），一次恢复就把根外那个文件写穿。
     */
    @Test
    void restoreDoesNotWriteThroughASymlinkToAFileOutsideTheRoot() throws Exception {
        Fixture fixture = new Fixture();
        Path victim = fixture.outside.resolve("secret.txt");
        byte[] before = Files.readAllBytes(victim);
        fixture.symlink(fixture.project.resolve("linked.env"), victim);

        fixture.manager.preTurnSnapshot("t1", "before");
        Files.writeString(fixture.project.resolve("normal.txt"), "changed\n");
        fixture.manager.restorePreTurn(1);

        assertArrayEquals(before, Files.readAllBytes(victim),
                "根外文件被写穿了：恢复期的围栏是词法的，normalize() 不解析符号链接");
    }

    /**
     * 提权变体：快照捕获时 {@code assets/} 是真目录，之后变成指向根外目录的软链
     * （可来自 agent 检出的不可信仓库内容、npm postinstall、MCP 文件工具、或一次被批准的
     * {@code execute_command}）。恢复 {@code assets/planted.txt} 不得在根外落下任何东西。
     *
     * <p>判据比对根外目录**整棵清单**的前后快照，不是只查 {@code planted.txt}：写和删都在这条路径上。
     */
    @Test
    void restoreDoesNotWriteThroughASymlinkToADirectoryOutsideTheRoot() throws Exception {
        Fixture fixture = new Fixture();
        Path assets = fixture.project.resolve("assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("planted.txt"), "planted\n");
        fixture.manager.preTurnSnapshot("t1", "assets is a real directory here");

        Files.delete(assets.resolve("planted.txt"));
        Files.delete(assets);
        fixture.symlink(assets, fixture.outside.resolve("dir"));
        Map<String, String> before = fixture.listing(fixture.outside.resolve("dir"));

        fixture.manager.restorePreTurn(1);

        assertEquals(before, fixture.listing(fixture.outside.resolve("dir")),
                "根外目录的内容被恢复改动了：软链目录把写带出了项目根");
    }

    /**
     * 删除侧同样不得越界：{@code linked.env} 在当前树里、不在目标树里，
     * 于是它会被删——删掉的必须是**那条链接**，不是它指向的根外文件。
     */
    @Test
    void restoreDeletesTheSymlinkItselfNotItsTargetOutsideTheRoot() throws Exception {
        Fixture fixture = new Fixture();
        Path victim = fixture.outside.resolve("secret.txt");
        byte[] before = Files.readAllBytes(victim);

        fixture.manager.preTurnSnapshot("t1", "no linked.env yet");
        fixture.symlink(fixture.project.resolve("linked.env"), victim);
        fixture.manager.restorePreTurn(1);

        assertTrue(Files.exists(victim), "根外文件被删掉了：删除侧跟着软链走了");
        assertArrayEquals(before, Files.readAllBytes(victim), "根外文件被改动了");
    }

    /**
     * 符号链接条目按 mode 重建，绝不把 blob 内容当普通文件字节写出——mode 120000 的 blob
     * 内容**就是链接目标字符串**，写出去得到的是一个内容为 {@code "normal.txt"} 的普通文件。
     */
    @Test
    void restoreRebuildsASymlinkEntryAsALinkNotAsAFileHoldingTheTargetPath() throws Exception {
        Fixture fixture = new Fixture();
        Path link = fixture.project.resolve("inside.link");
        fixture.symlink(link, Path.of("normal.txt"));

        fixture.manager.preTurnSnapshot("t1", "before");
        Files.delete(link);
        fixture.manager.restorePreTurn(1);

        assertTrue(Files.isSymbolicLink(link), "软链条目被恢复成了普通文件，而不是重建为链接");
        assertEquals("hello\n", Files.readString(link), "链接内容应当是它指向的文件，不是目标路径字符串");
    }

    /** 指向根外的软链在恢复后仍是软链，且没有被写穿。 */
    @Test
    void restoreLeavesAnEscapingSymlinkAsALink() throws Exception {
        Fixture fixture = new Fixture();
        Path link = fixture.project.resolve("linked.env");
        fixture.symlink(link, fixture.outside.resolve("secret.txt"));

        fixture.manager.preTurnSnapshot("t1", "before");
        Files.writeString(fixture.project.resolve("normal.txt"), "changed\n");
        fixture.manager.restorePreTurn(1);

        assertTrue(Files.isSymbolicLink(link), "软链被换成了普通文件");
        assertEquals("PROD_SECRET=keepme\n", Files.readString(link),
                "链接目标的内容变了——写穿了");
    }

    /**
     * 反例桩。没有这一条，一个直接 {@code return} 的 {@code writeTargetTree} 能让上面所有断言全绿——
     * 围栏的价值在于「挡住越界、放行正常」，只钉住前一半的判据是半个判据。
     */
    @Test
    void restoreStillRewritesOrdinaryFilesInsideTheRoot() throws Exception {
        Fixture fixture = new Fixture();
        fixture.symlink(fixture.project.resolve("linked.env"), fixture.outside.resolve("secret.txt"));

        fixture.manager.preTurnSnapshot("t1", "before");
        Files.writeString(fixture.project.resolve("normal.txt"), "changed\n");
        Files.writeString(fixture.project.resolve("added.txt"), "added\n");
        RestoreResult result = fixture.manager.restorePreTurn(1);

        assertTrue(result.success(), "恢复应当成功: " + result);
        assertEquals("hello\n", Files.readString(fixture.project.resolve("normal.txt")),
                "根内的普通文件没有被恢复");
        assertFalse(Files.exists(fixture.project.resolve("added.txt")),
                "目标树里没有的根内文件应当被删掉");
    }

    // --- fixture ---

    private final class Fixture {
        private final Path outside = tempDir.resolve("outside");
        private final Path project = tempDir.resolve("proj");
        private final SideGitManager manager;

        private Fixture() throws IOException {
            Files.createDirectories(outside.resolve("dir"));
            Files.createDirectories(project);
            Files.writeString(outside.resolve("secret.txt"), "PROD_SECRET=keepme\n");
            Files.writeString(outside.resolve("dir").resolve("kept.txt"), "kept\n");
            Files.writeString(project.resolve("normal.txt"), "hello\n");
            manager = new SideGitManager(project,
                    new SnapshotConfig(true, tempDir.resolve("snap"), 50, List.of(".git", "target")));
        }

        /** 目录整棵清单：相对路径 → 内容。写和删都会让它变。 */
        private Map<String, String> listing(Path dir) throws IOException {
            Map<String, String> files = new TreeMap<>();
            try (var walk = Files.walk(dir)) {
                for (Path path : walk.filter(Files::isRegularFile).toList()) {
                    files.put(dir.relativize(path).toString(), Files.readString(path));
                }
            }
            return files;
        }

        /** Windows 上建软链需要权限；拿不到就 skip，不是 pass。 */
        private void symlink(Path link, Path target) {
            try {
                Files.createSymbolicLink(link, target);
            } catch (IOException | UnsupportedOperationException e) {
                assumeTrue(false, "本平台无法创建符号链接: " + e);
            }
            assertTrue(Files.isSymbolicLink(link), "fixture 前提：" + link + " 是符号链接");
        }
    }
}
