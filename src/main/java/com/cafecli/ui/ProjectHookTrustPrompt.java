package com.cafecli.ui;

import com.cafecli.render.Renderer;
import org.jline.reader.LineReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Startup-only trust prompt: uses the same resident reader before its REPL takes ownership. */
public final class ProjectHookTrustPrompt {
    private final Renderer renderer;
    private final LineReader reader;

    public ProjectHookTrustPrompt(Renderer renderer, LineReader reader) {
        this.renderer = Objects.requireNonNull(renderer);
        this.reader = Objects.requireNonNull(reader);
    }

    /** An interrupt propagates to the whole Hook loader; only an explicit answer is a trust decision. */
    public boolean confirm(Path project, List<String> commands) {
        renderer.stream().println("⚠️ 检测到未信任的项目级 hooks.json（" + project + "）。");
        renderer.stream().println("   以下命令将随工具生命周期在你的机器上运行：");
        commands.forEach(command -> renderer.stream().println("     • " + command));
        try {
            renderer.beforeInput();
            String answer = reader.readLine("   信任并运行本项目的 hooks？[y/N] ");
            return answer != null && answer.trim().toLowerCase(Locale.ROOT).startsWith("y");
        } finally { renderer.afterInput(); }
    }
}
