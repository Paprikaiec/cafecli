package com.cafecli.tool;

import com.cafecli.runtime.CancellationContext;
import com.cafecli.tool.ToolRegistry.ToolExecutionResult;
import com.cafecli.tool.ToolRegistry.ToolInvocation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * 同一轮工具调用的批次执行器（{@link ToolRegistry#executeTools} 的实现）：单个调用就地执行；多个调用在定长线程池
 * （上限 {@value #MAX_PARALLEL_TOOLS}）里并行，结果保持传入顺序，超过批次超时的调用被取消并回填超时结果。
 *
 * <p>每个调用都经注入的 {@code executeToolOutput}——绑定到 registry 的<b>虚方法</b>，HITL 覆写与测试替身因此照常拦截。
 * MCP 大结果先逐条转引用，再对整批做合计预算（{@link McpResultBudget}）。
 */
final class ToolBatchExecutor {
    private static final int MAX_PARALLEL_TOOLS = 4;

    private final BiFunction<String, String, ToolOutput> executeToolOutput;
    private final McpResultBudget mcpResultBudget;
    private final long toolBatchTimeoutSeconds;

    ToolBatchExecutor(BiFunction<String, String, ToolOutput> executeToolOutput,
                      McpResultBudget mcpResultBudget,
                      long toolBatchTimeoutSeconds) {
        this.executeToolOutput = executeToolOutput;
        this.mcpResultBudget = mcpResultBudget;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
    }

    List<ToolExecutionResult> execute(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (CancellationContext.isCancelled()) {
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(
                            invocation, ToolOutput.Status.CANCELLED, "用户取消了此次工具调用"))
                    .toList();
        }
        if (invocations.size() == 1) {
            ToolInvocation invocation = invocations.get(0);
            long startedAt = System.nanoTime();
            ToolOutput output = executeToolOutput.apply(invocation.name(), invocation.argumentsJson());
            output = mcpResultBudget.referenceLargeMcpResults(invocation, output);
            return mcpResultBudget.applyMcpBatchBudget(
                    List.of(ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt))));
        }

        int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "cafecli-tool-executor");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                    .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                        if (CancellationContext.isCancelled()) {
                            return ToolExecutionResult.failed(
                                    invocation, ToolOutput.Status.CANCELLED, "用户取消了此次工具调用");
                        }
                        long startedAt = System.nanoTime();
                        ToolOutput output = executeToolOutput.apply(invocation.name(), invocation.argumentsJson());
                        output = mcpResultBudget.referenceLargeMcpResults(invocation, output);
                        return ToolExecutionResult.completed(invocation, output, elapsedMillis(startedAt));
                    })
                    .toList();

            List<Future<ToolExecutionResult>> futures =
                    executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ToolInvocation invocation = invocations.get(i);
                Future<ToolExecutionResult> future = futures.get(i);
                if (future.isCancelled()) {
                    results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                    continue;
                }

                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ToolExecutionResult.failed(invocation, ToolOutput.Status.CANCELLED, "工具执行被中断"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause == null || cause.getMessage() == null
                            ? "未知错误"
                            : cause.getMessage();
                    results.add(ToolExecutionResult.failed(invocation, ToolOutput.Status.ERROR, message));
                }
            }
            return mcpResultBudget.applyMcpBatchBudget(results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(
                            invocation, ToolOutput.Status.CANCELLED, "工具批次执行被中断"))
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }
}
