package com.paicli.cli;

import java.nio.file.Path;
import java.util.Locale;

/**
 * `cafecli run` 子命令的参数：issue 文件（必填）+ trace 输出文件（可选）+ 执行策略。
 *
 * <p>策略默认 {@code react}（自主 ReAct 自编排）。确定性多 Agent 编排（{@code /team}）已退役
 * （ADR 0004），无人值守/SWE-bench 一律走 {@code --strategy=react} 还原 agent 自身能力、不启用
 * 任何确定性编排脚手架。
 */
public record RunCommand(Path issueFile, Path traceFile, String strategy) {

    static final String DEFAULT_STRATEGY = "react";

    /** 便捷构造：默认 react 策略（保持旧调用点 2 参兼容）。 */
    public RunCommand(Path issueFile, Path traceFile) {
        this(issueFile, traceFile, DEFAULT_STRATEGY);
    }

    public static RunCommand parse(String[] args) {
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
                case "--file" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--file 需要一个参数");
                    }
                    issueFile = Path.of(args[++i]);
                }
                case "--trace-file" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--trace-file 需要一个参数");
                    }
                    traceFile = Path.of(args[++i]);
                }
                case "--strategy" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--strategy 需要一个参数");
                    }
                    strategy = args[++i].toLowerCase(Locale.ROOT).trim();
                }
                default -> { /* 忽略未知参数，保持向前兼容 */ }
            }
        }
        if (issueFile == null) {
            throw new IllegalArgumentException("cafecli run 需要 --file <issue.md>");
        }
        if (strategy.isBlank()) {
            strategy = DEFAULT_STRATEGY;
        }
        if (!DEFAULT_STRATEGY.equals(strategy)) {
            throw new IllegalArgumentException(
                    "不支持的 --strategy=" + strategy
                            + "；无人值守当前仅支持 react（自主 ReAct 自编排，不启用确定性编排）");
        }
        return new RunCommand(issueFile, traceFile, strategy);
    }
}
