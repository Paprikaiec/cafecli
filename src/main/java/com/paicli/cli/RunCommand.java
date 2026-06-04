package com.paicli.cli;

import java.nio.file.Path;
import java.util.Locale;

/** `paicli run` 子命令的参数：issue 文件（必填）+ trace 输出文件（可选）。 */
public record RunCommand(Path issueFile, Path traceFile) {

    public static RunCommand parse(String[] args) {
        Path issueFile = null;
        Path traceFile = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i].toLowerCase(Locale.ROOT)) {
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
                default -> { /* 忽略未知参数，保持向前兼容 */ }
            }
        }
        if (issueFile == null) {
            throw new IllegalArgumentException("paicli run 需要 --file <issue.md>");
        }
        return new RunCommand(issueFile, traceFile);
    }
}
