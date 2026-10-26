package com.cafecli.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** A real controlling PTY reaches ExecPty's stty/spawn-helper setup and cleanup, unlike pipe rigs. */
@EnabledOnOs({OS.MAC,OS.LINUX})
class NativeInputBoundaryTest {
    @TempDir Path root;

    @Test @Timeout(90)
    void nativeCtrlCAfterAnEarlierReadCannotKillSetupOrCleanupAndPendingWriteRemainsCancelled() throws Exception {
        Path script = root.resolve("native-cancel.py");
        try (var input = getClass().getResourceAsStream("/ui/native-cancel.py")) {
            assertNotNull(input); Files.copy(input,script);
        }
        var failures = new ArrayList<String>();
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 12; i++) {
                Path iteration = root.resolve("run-"+i);
                jobs.add(workers.submit(() -> {
                    var process = new ProcessBuilder("python3",script.toString(),
                            Path.of(System.getProperty("java.home"),"bin/java").toString(),
                            System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                            iteration.toString()).redirectErrorStream(true).start();
                    boolean ended = process.waitFor(30,TimeUnit.SECONDS);
                    if (!ended) {
                        process.destroy();
                        if (!process.waitFor(3,TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
                    }
                    String output = new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    return ended && process.exitValue()==0 ? null : iteration+"\n"+output;
                }));
            }
            for (var job : jobs) { String failure=job.get(); if (failure!=null) failures.add(failure); }
        }
        assertTrue(failures.isEmpty(),String.join("\n",failures));
    }
}
