package com.paicli.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.runtime.CancellationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Shell 租户（外部 Hook 租户）：把用户在 {@code hooks.json} 里配的命令按统一契约跑成一个 {@link HookTenant}。
 *
 * 契约（ADR 0012，对齐 Claude Code 公开 hook 模型）：
 * <ul>
 *   <li>payload JSON 从 stdin 进程；环境变量 {@code PAICLI_PROJECT_DIR} = 项目根；工作目录 = 项目根。</li>
 *   <li>exit 0 → CONTINUE；stdout 若是 JSON 决定超集（{@code decision}/{@code reason}/{@code additionalContext}/
 *       {@code statusMessage}）则解析成对应 {@link HookDecision}。</li>
 *   <li>exit 2 → BLOCK，stderr 作为回灌模型的原因。</li>
 *   <li>其他退出码 → 用户可见告警，**绝不阻断**（fail-open）。</li>
 *   <li>超时（默认 60s，硬顶 10min）与崩溃都 fail-open（CONTINUE + 告警）。</li>
 *   <li>进程随当前回合取消令牌走：ESC → {@code destroyForcibly}。</li>
 * </ul>
 * matcher 是工具名正则，spawn 之前判定——不命中直接 CONTINUE、不起进程。
 * 同步跑在调用方线程（主串行执行器或任务虚拟线程），从不读终端；{@code statusMessage} 经注入的 sink 走 renderer。
 *
 * hook 命令免 CommandGuard 与逐次 HITL（ADR 0012：守卫防模型，hook 是用户签署的配置）。
 */
public class ShellHook implements HookTenant {

    private static final Logger log = LoggerFactory.getLogger(ShellHook.class);

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    private static final long POLL_MILLIS = 50;

    private final String name;
    private final Pattern matcher;      // null = 匹配该事件全部工具
    private final String command;
    private final Duration timeout;
    private final String statusMessage; // null / blank = 不发状态
    private final Consumer<String> statusSink;
    private final ObjectMapper mapper;

    public ShellHook(String name, Pattern matcher, String command, Duration timeout,
                     String statusMessage, Consumer<String> statusSink, ObjectMapper mapper) {
        this.name = name == null || name.isBlank() ? "shell-hook" : name;
        this.matcher = matcher;
        this.command = command;
        this.timeout = clampTimeout(timeout);
        this.statusMessage = statusMessage;
        this.statusSink = statusSink == null ? msg -> { } : statusSink;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    private static Duration clampTimeout(Duration configured) {
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return DEFAULT_TIMEOUT;
        }
        return configured.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : configured;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public HookDecision evaluate(HookPayload payload) {
        if (payload == null) {
            return HookDecision.cont();
        }
        if (matcher != null && !matcher.matcher(nullToEmpty(payload.toolName())).find()) {
            return HookDecision.cont();  // matcher 不命中：不 spawn
        }
        if (statusMessage != null && !statusMessage.isBlank()) {
            statusSink.accept(statusMessage);
        }
        try {
            return run(payload);
        } catch (Exception e) {
            log.warn("⚠️ 生命周期 Hook '{}' 执行失败，已放行（fail-open）: {}", name, e.toString());
            return HookDecision.cont();  // 崩溃 fail-open
        }
    }

    private HookDecision run(HookPayload payload) throws Exception {
        String json = mapper.writeValueAsString(payload);
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.environment().put("PAICLI_PROJECT_DIR", nullToEmpty(payload.projectRoot()));
        Path workDir = workDir(payload.projectRoot());
        if (workDir != null) {
            pb.directory(workDir.toFile());
        }
        Process process = pb.start();

        StreamDrainer stdout = StreamDrainer.start(process.getInputStream());
        StreamDrainer stderr = StreamDrainer.start(process.getErrorStream());
        writeStdin(process, json);

        boolean cancelled = false;
        boolean timedOut = false;
        long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive()) {
            if (CancellationContext.isCancelled()) {
                cancelled = true;
                break;
            }
            if (System.nanoTime() >= deadline) {
                timedOut = true;
                break;
            }
            process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS);
        }

        if (cancelled || timedOut) {
            process.destroyForcibly();
            if (timedOut) {
                log.warn("⚠️ 生命周期 Hook '{}' 超时（{}s），已放行（fail-open）", name, timeout.toSeconds());
            }
            return HookDecision.cont();  // 超时 / 取消都 fail-open
        }

        int exit = process.exitValue();
        return interpret(exit, stdout.text(), stderr.text());
    }

    private HookDecision interpret(int exit, String stdout, String stderr) {
        if (exit == 0) {
            HookDecision parsed = parseJsonDecision(stdout);
            return parsed != null ? parsed : HookDecision.cont();
        }
        if (exit == 2) {
            String reason = stderr == null || stderr.isBlank()
                    ? "Hook '" + name + "' 阻断（exit 2，无 stderr）"
                    : stderr.trim();
            return HookDecision.block(reason);
        }
        log.warn("⚠️ 生命周期 Hook '{}' 以退出码 {} 结束（stderr: {}），已放行（不阻断）",
                name, exit, stderr == null ? "" : stderr.trim());
        return HookDecision.cont();  // 非 0 非 2：告警但不阻断
    }

    /** exit 0 时解析 stdout 的 JSON 决定超集；非 JSON / 解析失败返回 null（视为 CONTINUE）。 */
    private HookDecision parseJsonDecision(String stdout) {
        if (stdout == null || stdout.isBlank() || stdout.stripLeading().charAt(0) != '{') {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(stdout);
            String decision = node.hasNonNull("decision") ? node.get("decision").asText().trim().toLowerCase() : "continue";
            String reason = node.hasNonNull("reason") ? node.get("reason").asText() : null;
            String context = node.hasNonNull("additionalContext") ? node.get("additionalContext").asText() : null;
            String status = node.hasNonNull("statusMessage") ? node.get("statusMessage").asText() : null;
            HookDecision base = switch (decision) {
                case "block" -> HookDecision.block(reason);
                case "allow" -> HookDecision.allow();
                case "ask" -> HookDecision.ask();
                default -> HookDecision.cont();
            };
            if (context != null && !context.isBlank()) {
                base = base.withAdditionalContext(context);
            }
            if (status != null && !status.isBlank()) {
                base = base.withStatusMessage(status);
            }
            return base;
        } catch (Exception e) {
            log.warn("⚠️ 生命周期 Hook '{}' stdout 不是合法 JSON 决定，已忽略: {}", name, e.toString());
            return null;
        }
    }

    private static void writeStdin(Process process, String json) {
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // 子进程可能不读 stdin 就退出（broken pipe），忽略。
        }
    }

    private static Path workDir(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            return null;
        }
        Path path = Path.of(projectRoot);
        return Files.isDirectory(path) ? path : null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 后台线程排空进程 stdout/stderr，避免管道缓冲写满导致子进程阻塞（经典死锁）。 */
    private static final class StreamDrainer {
        private final Thread thread;
        private final StringBuilder buffer = new StringBuilder();

        private StreamDrainer(java.io.InputStream in) {
            this.thread = new Thread(() -> {
                try (java.io.InputStream stream = in) {
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = stream.read(chunk)) != -1) {
                        synchronized (buffer) {
                            buffer.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                } catch (Exception ignored) {
                    // 进程被杀 / 流关闭：读到什么算什么。
                }
            }, "paicli-hook-stream");
            this.thread.setDaemon(true);
        }

        static StreamDrainer start(java.io.InputStream in) {
            StreamDrainer drainer = new StreamDrainer(in);
            drainer.thread.start();
            return drainer;
        }

        String text() {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (buffer) {
                return buffer.toString();
            }
        }
    }
}
