package com.cafecli.app;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The arguments of {@code cafecli run}, parsed exactly as v1's {@code cli/RunCommand} parses them — same flags, same
 * case-insensitivity, unknown arguments ignored, same error texts — so the command line is the same under the v2 flag.
 * Paths are kept as given; the entry resolves them against the working directory it was handed.
 *
 * @param issueFile the {@code --file} argument
 * @param traceFile the {@code --trace-file} argument, or {@code null}
 */
record RunArgs(Path issueFile, Path traceFile) {

    private static final String DEFAULT_STRATEGY = "react";

    /**
     * @param args the whole command line, {@code run} first
     * @throws IllegalArgumentException with v1's text, if {@code --file} is missing, a flag lacks its value, or the
     *                                  strategy is not {@code react}
     */
    static RunArgs parse(String[] args) {
        Path issueFile = null;
        Path traceFile = null;
        String strategy = DEFAULT_STRATEGY;
        for (int i = 1; i < args.length; i++) {
            String lower = args[i].toLowerCase(Locale.ROOT);
            if (lower.startsWith("--strategy=")) {
                strategy = lower.substring("--strategy=".length()).trim();
                continue;
            }
            switch (lower) {
                case "--file" -> issueFile = Path.of(value(args, ++i, "--file"));
                case "--trace-file" -> traceFile = Path.of(value(args, ++i, "--trace-file"));
                case "--strategy" -> strategy = value(args, ++i, "--strategy").toLowerCase(Locale.ROOT).trim();
                default -> {
                    // v1 ignores unknown arguments, for forward compatibility.
                }
            }
        }
        if (issueFile == null) {
            throw new IllegalArgumentException("cafecli run 需要 --file <issue.md>");
        }
        if (strategy.isBlank()) {
            strategy = DEFAULT_STRATEGY;
        }
        if (!DEFAULT_STRATEGY.equals(strategy)) {
            throw new IllegalArgumentException("不支持的 --strategy=" + strategy
                    + "；无人值守当前仅支持 react（自主 ReAct 自编排，不启用确定性编排）");
        }
        return new RunArgs(issueFile, traceFile);
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " 需要一个参数");
        }
        return args[index];
    }
}
