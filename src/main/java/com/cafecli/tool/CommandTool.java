package com.cafecli.tool;

import com.cafecli.policy.CommandGuard;
import com.cafecli.policy.PolicyException;
import com.cafecli.tool.ToolOutput.Status;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.cafecli.tool.ToolParam.createParameters;

/**
 * {@code execute_command}：在项目根目录下跑短时 Shell 命令。
 *
 * <p>工作目录与输出观察者都经 {@link ToolEnv} 在<b>每次调用时</b>现取（子任务 / {@code setProjectPath} 会在注册后换掉它们）。
 * 命令围栏 {@link CommandGuard} 的拒绝以 {@link PolicyException} 抛出，由 {@code 工具执行适配器} 统一审计与格式化。
 */
final class CommandTool {
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;

    private final ToolEnv env;
    private final long commandTimeoutSeconds;

    CommandTool(ToolEnv env, long commandTimeoutSeconds) {
        this.env = env;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    List<Tool> tools() {
        return List.of(new Tool(
                "execute_command",
                "在当前项目目录中执行短时 Shell 命令（默认 60 秒超时，不允许全盘扫描）",
                createParameters(new ToolParam("command", "string", "要执行的命令", true)),
                args -> executeCommand(ToolArgs.of(args).str("command"))
        ));
    }

    private ToolOutput executeCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            return ToolOutput.failure(Status.ERROR, "执行命令失败: 命令不能为空");
        }
        String denyReason = CommandGuard.check(normalized);
        if (denyReason != null) {
            // 抛 PolicyException 让外层 executeTool 统一写 audit 并格式化拒绝消息，
            // 命令围栏与路径围栏的拒绝路径走同一个出口。
            throw new PolicyException(denyReason);
        }

        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "cafecli-command-output");
            thread.setDaemon(true);
            return thread;
        });

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", normalized);
            pb.directory(new File(env.projectPath().get()));
            pb.redirectErrorStream(true);
            process = pb.start();

            Process runningProcess = process;
            Future<String> outputFuture =
                    outputReaderExecutor.submit(() -> readProcessOutput(runningProcess, normalized));

            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                return ToolOutput.failure(Status.TIMEOUT, "命令执行超时（" + commandTimeoutSeconds + "秒），已强制终止");
            }

            String output = getCommandOutput(outputFuture);
            int exitCode = process.exitValue();
            // 命令跑完了就是 OK：非零退出码是命令自己的结果，留在文本里给模型看，不是工具失败
            return ToolOutput.text(String.format("命令执行完成 (exit code: %d)\n%s", exitCode, output));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return ToolOutput.failure(Status.CANCELLED, "用户取消了此次工具调用");
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return ToolOutput.failure(Status.ERROR, "执行命令失败: " + e.getMessage());
        } finally {
            outputReaderExecutor.shutdownNow();
        }
    }

    /**
     * 在专用线程上逐行读命令输出：既累积成最终结果，也<b>逐行发布</b>给观察者（ADR 0017 D5）。
     *
     * <p>发布与累积的上限彼此独立：{@code MAX_COMMAND_OUTPUT_CHARS} 管的是回灌给模型的那份文本，
     * 而观察者看到的是命令此刻在说什么——一条超长命令后半程的输出照样要能出现在[[活区尾]]里，
     * 否则用户看到的是一片停住的屏幕。
     */
    private String readProcessOutput(Process process, String command) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    env.commandOutputObserver().get().accept(command, line);
                } catch (Exception ignored) {
                    // observer 失败不能影响命令主路径（同 writeFileObserver）
                }
                if (output.length() < MAX_COMMAND_OUTPUT_CHARS) {
                    int remaining = MAX_COMMAND_OUTPUT_CHARS - output.length();
                    if (line.length() > remaining) {
                        output.append(line, 0, remaining);
                    } else {
                        output.append(line);
                    }
                    output.append("\n");
                }
            }
        }
        if (output.length() >= MAX_COMMAND_OUTPUT_CHARS) {
            return output.substring(0, MAX_COMMAND_OUTPUT_CHARS) + "\n...(输出已截断)";
        }
        return output.toString();
    }

    private String getCommandOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(命令已结束，但输出读取超时)";
        }
    }
}
