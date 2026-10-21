package com.cafecli.app;

import com.cafecli.core.BudgetReason;
import com.cafecli.core.RunOutcome;
import com.cafecli.harness.AgentSession;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import com.cafecli.harness.config.ConfigWarning;
import com.cafecli.harness.config.HarnessConfig;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/**
 * The v2 command line ({@code -Dcafecli.v2=true}). So far it runs exactly one command, headless {@code run --file
 * <issue.md>}: load the configuration, compose the session, submit the issue file's text, print the final answer as
 * plain text on stdout. Synchronously — no event loop, no pump, no push. Every other command, a bare (interactive)
 * launch and {@code --trace-file} are refused as not available in v2 yet; {@code serve --http}, as removed.
 *
 * <p>Inputs and outputs are all parameters, so it runs the same in a test as from the real entry
 * ({@link SystemEntry}), which alone touches the process. It returns the exit code instead of exiting, and uses
 * streams and exit codes exactly as v1's {@code run} does:
 * <ul>
 *   <li>a run that started prints its answer on stdout and exits {@value #OK} whatever its outcome — v1's
 *       {@code Agent.run} returns its cancelled / budget / LLM-failure texts as the answer, and v1's {@code Main}
 *       prints the answer;</li>
 *   <li>a run that cannot start (no usable provider, unreadable issue file) says why on stderr and exits
 *       {@value #FAILED};</li>
 *   <li>a command line it cannot run says why on stderr and exits {@value #USAGE} (v1 exits 2 when {@code run}'s
 *       arguments do not parse).</li>
 * </ul>
 *
 * <p>The user-facing texts live here, not in {@code core} or {@code harness}. Where v1 has the same line, it is v1's
 * wording; messages about v2 itself are new.
 */
public final class Cli {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    private Cli() {
    }

    public static int run(String[] args, ConfigSources sources, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println(notAvailable("interactive mode"));
            return USAGE;
        }
        if (isServeHttp(args)) {
            // PRD intentional change 7: the Runtime API is not carried over.
            err.println("❌ removed in v2: `serve --http`. v2 has no Runtime API (an RPC mode may replace it after "
                    + "switch-over); run without -Dcafecli.v2=true for v1.");
            return USAGE;
        }
        if (!"run".equalsIgnoreCase(args[0])) {
            err.println(notAvailable("the `" + args[0] + "` command"));
            return USAGE;
        }
        RunArgs run;
        try {
            run = RunArgs.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("❌ " + e.getMessage());
            return USAGE;
        }
        if (run.traceFile() != null) {
            err.println(notAvailable("--trace-file"));
            return USAGE;
        }

        HarnessConfig config = ConfigLoader.load(sources);
        for (ConfigWarning warning : config.warnings()) {
            err.println("⚠️ 配置文件读取失败，使用默认配置: " + warning.detail());
        }
        AgentSession session;
        try {
            session = Composition.headlessSession(config);
        } catch (Composition.Unavailable e) {
            err.println(e.getMessage());
            return FAILED;
        }
        String issue;
        try {
            issue = Files.readString(sources.workingDirectory().resolve(run.issueFile()));
        } catch (IOException e) {
            err.println("❌ 运行失败: " + e.getMessage());
            return FAILED;
        }
        return report(session.submit(issue), out);
    }

    /**
     * Prints how the run ended as v1 does: its answer — for an outcome other than completed, v1's text for it — on
     * stdout, nothing when the answer is blank; exit {@value #OK} either way.
     */
    static int report(RunOutcome outcome, PrintStream out) {
        String answer = switch (outcome) {
            case RunOutcome.Completed completed -> completed.message().text();
            case RunOutcome.Cancelled ignored -> "⏹️ 已取消当前任务。";
            case RunOutcome.BudgetExhausted exhausted -> "❌ " + describe(exhausted.reason());
            case RunOutcome.Failed failed -> "❌ 调用 LLM 失败: " + failed.detail();
        };
        if (!answer.isBlank()) {
            out.println(answer);
        }
        return OK;
    }

    /** v1's {@code AgentBudget.describeExit} text for the reasons v2 has so far. */
    private static String describe(BudgetReason reason) {
        if (reason instanceof BudgetReason.IterationCap cap) {
            return String.format(Locale.ROOT, "达到硬轮数上限（%d），已强制收尾", cap.maxIterations());
        }
        // The harness's own budgets (ticket 12) bring v1's texts for theirs.
        return "预算已用尽（" + reason + "），已强制收尾";
    }

    /** v1's {@code Main.isRuntimeServeCommand}: {@code serve} with {@code --http} anywhere, in any case. */
    private static boolean isServeHttp(String[] args) {
        return "serve".equalsIgnoreCase(args[0]) && Arrays.stream(args).anyMatch("--http"::equalsIgnoreCase);
    }

    private static String notAvailable(String what) {
        return notAvailable(what, "v2 only runs `run --file <issue.md>` so far");
    }

    /** "{@code what} is not available in v2 yet", with what v2 can do instead and the way back to v1. */
    static String notAvailable(String what, String soFar) {
        return "❌ not available in v2 yet: " + what + ". " + soFar + "; run without -Dcafecli.v2=true for v1.";
    }
}
