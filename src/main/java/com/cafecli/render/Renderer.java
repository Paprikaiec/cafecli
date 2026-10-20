package com.cafecli.render;

import com.cafecli.hitl.ApprovalRequest;
import com.cafecli.hitl.ApprovalResult;
import com.cafecli.util.TerminalMarkdownRenderer;

import java.io.PrintStream;
import java.util.List;

/**
 * 终端渲染器抽象。
 *
 * <p>把对话流核心交互（流式输出、工具调用、HITL、状态栏、行内 diff、palette）
 * 收口到一个接口，方便 inline 流式 / plain 两种形态切换。
 *
 * <p>启动期一次性输出（banner、MCP/Skill 摘要等）仍可走原有 stdout；
 * 对话期的流、工具块、状态栏、输入区应尽量经过 Renderer，避免多个组件
 * 直接争抢终端光标。
 *
 * <p>线程模型：所有方法应在调用方线程同步返回；
 * 涉及异步渲染线程的实现负责自己做线程封送。
 */
public interface Renderer extends AutoCloseable {

    /** 启动渲染器（例如设置滚动区域、启动 GUI 主循环）。Main 必须先调用一次。 */
    void start();

    /** 开始一次用户任务输出。默认 no-op；inline renderer 用它重置本轮可重绘 transcript。 */
    default void beginTurn() {
    }

    /** Complete the current user turn. Default renderers have no turn-finalization work. */
    default void endTurn() {
    }

    /** Clear renderer-owned conversation view state. Non-retained renderers have no work. */
    default void clearView() {
    }

    /** 进入用户输入前。inline renderer 用它刷新输入周边状态。 */
    default void beforeInput() {
    }

    /** 用户输入结束后。inline renderer 用它恢复输入周边状态。 */
    default void afterInput() {
    }

    /** 当前渲染器是否支持独立的模型思考面板。 */
    default boolean supportsThinkingPanel() {
        return false;
    }

    /** 开始显示模型思考面板。plain renderer 保持 no-op，继续用正文流式输出。 */
    default void beginThinking(String label) {
    }

    /** 追加模型 reasoning delta 到思考面板。 */
    default void appendThinking(String delta) {
    }

    /** 结束并清理模型思考面板。 */
    default void endThinking() {
    }

    /** 当前渲染器希望 LineReader 使用的左侧输入提示。 */
    default String inputPrompt() {
        return "> ";
    }

    /** 当前渲染器希望 LineReader 使用的右侧提示；返回 null 表示不显示。 */
    default String inputRightPrompt() {
        return null;
    }

    @Override
    void close();

    /**
     * 流式输出的目标 PrintStream。
     *
     * <p>Agent.StreamRenderer routes structured reasoning / answer / code through the typed
     * block methods below. This stream remains the fallback for unstructured text and for
     * renderer implementations that keep the default block behavior.
     *
     * <p>对 InlineRenderer / PlainRenderer 而言这就是 {@code System.out}。
     */
    PrintStream stream();

    /** Stateful raw-source channel for one streamed text block. */
    interface BlockWriter {
        void append(String sourceDelta);

        void finish();
    }

    /** Append the submitted user prompt to the current turn. */
    default void appendUserPrompt(String text) {
        if (text != null && !text.isEmpty()) {
            stream().print(text);
        }
    }

    /** Begin one answer-prose block. Source deltas remain raw markdown. */
    default BlockWriter beginAnswer() {
        return markdownBlockWriter();
    }

    /** Begin one visible reasoning block. Source deltas remain raw markdown. */
    default BlockWriter beginReasoning() {
        return markdownBlockWriter();
    }

    /** Render one fenced code block, preserving its source language. */
    default void appendCodeBlock(String language, String code) {
        String normalizedLanguage = language == null ? "" : language.trim();
        String normalizedCode = code == null ? "" : code;
        StringBuilder markdown = new StringBuilder("```").append(normalizedLanguage).append('\n')
                .append(normalizedCode);
        if (!normalizedCode.endsWith("\n")) {
            markdown.append('\n');
        }
        markdown.append("```\n");
        TerminalMarkdownRenderer markdownRenderer =
                new TerminalMarkdownRenderer(stream(), this::terminalColumns);
        markdownRenderer.append(markdown.toString());
        markdownRenderer.finish();
    }

    private BlockWriter markdownBlockWriter() {
        TerminalMarkdownRenderer markdownRenderer =
                new TerminalMarkdownRenderer(stream(), this::terminalColumns);
        return new BlockWriter() {
            @Override
            public void append(String sourceDelta) {
                markdownRenderer.append(sourceDelta);
            }

            @Override
            public void finish() {
                markdownRenderer.finish();
            }
        };
    }

    /** 当前输出区域可用列数。Markdown 表格等宽布局按这个值做收缩和换行。 */
    default int terminalColumns() {
        return 120;
    }

    /**
     * 渲染一组工具调用的标签和关键参数。
     *
     * <p>InlineRenderer 把每条调用包装成可折叠块；
     * PlainRenderer 直接 println 当前 Agent 内已有的标签格式。
     */
    void appendToolCalls(List<ToolCallView> toolCalls);

    /**
     * The other half of the pair: the results of the calls last announced by
     * {@link #appendToolCalls(List)}, matched to them by {@code tool_call_id} (ADR 0017 D2).
     *
     * <p>A batch of calls is <b>one</b> block. {@code appendToolCalls} opens it in the [[活区]] with a
     * call header and no result; this event fills the result in and completes it, and only then is it
     * committed to [[冻结回卷]]. Splitting the two into separate blocks was rejected: a background
     * completion notice or a [[转向消息]]'s [[提交块]] can be committed in between and would tear the
     * pair apart.
     *
     * <p>Per ADR 0011's convention a new block event carries a default that falls back to
     * {@link #stream()}, so {@link PlainRenderer} and the spawner's no-op renderer need no change.
     * The fallback is deliberately width-agnostic — it caps in source lines, because a renderer that
     * has no block model has no frame width either.
     */
    default void appendToolResults(List<ToolResultView> results) {
        if (results == null) {
            return;
        }
        for (ToolResultView result : results) {
            String text = result == null ? null : result.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            List<String> lines = text.strip().lines().toList();
            int shown = Math.min(3, lines.size());
            for (String line : lines.subList(0, shown)) {
                stream().println("  " + line);
            }
            if (lines.size() > shown) {
                stream().println("  " + BlockOverflow.line(lines.size() - shown));
            }
        }
    }

    /**
     * One line a still-running {@code execute_command} just printed (ADR 0017 D5).
     *
     * <p>Not a block event: it feeds the <b>running tail</b> of the [[工具块]] that
     * {@link #appendToolCalls(List)} opened, and when the command returns
     * {@link #appendToolResults(List)} <em>replaces</em> that tail with the completed result. Running
     * shows the last lines, completed shows the first ones, and the two differ on purpose.
     *
     * <p>Only {@code execute_command} feeds it — that decision lives at {@code ToolRegistry}'s
     * per-line observer, because it is the one tool whose duration is unbounded and where the user
     * genuinely cannot tell a running command from a hung one.
     *
     * <p>The default is a <b>no-op</b>, not a {@link #stream()} fallback, which is where this differs
     * from the block events above: transient content has nowhere to live in a renderer with no live
     * region, and printing it there would put the command's output on screen twice — once as it
     * scrolled past and again inside the result. The producer thread must never write the terminal
     * from here; an implementation posts a render event and returns.
     */
    default void appendCommandOutput(String command, String line) {
        // Transient by definition: a renderer without a live region simply has no place to put it.
    }

    /**
     * 渲染一个文件 diff 块。
     *
     * @param filePath 文件路径
     * @param before   修改前内容（null 表示新建）
     * @param after    修改后内容（null 表示删除）
     */
    void appendDiff(String filePath, String before, String after);

    /** 更新底部状态栏。允许频繁调用，渲染器内部自行节流。 */
    void updateStatus(StatusInfo status);

    /**
     * 同步阻塞地展示 HITL 审批请求并收集决策。
     *
     * <p>实现需要保证在渲染线程之外调用时不死锁。
     */
    ApprovalResult promptApproval(ApprovalRequest request);

    /**
     * 呈现一条待决的[[审批应答]]，**不等待答案**（issue 07）。
     *
     * <p>答案经[[输入框]]提交，与普通提交同一条通路，所以「画卡片」与「读答案」是两件事、分处两条
     * 线程：卡片由渲染线程画、更新、移除，按键只由输入线程读。<b>渲染线程上任何终端读取都是死锁
     * bug</b>——这条纪律不变，而拆开之后它在结构上不可违反。
     *
     * <p>同一条审批可以被呈现多次：空提交表示「再问一次」，无法识别的答案也带一句提示重问。
     *
     * @param notice 给用户的一句提示（无提示时为空串），例如无法识别的答案
     */
    default void showApproval(ApprovalRequest request, String notice) {
    }

    /** 队列里再没有待决[[审批应答]]了：把卡片从[[活区]]撤掉。 */
    default void dismissApproval() {
    }

    /**
     * 显示一个临时浮起的选择列表，等待用户选定一项或取消。
     *
     * @return 选中项的下标；用户取消（Esc）返回 -1
     */
    int openPalette(String title, List<String> items);
}
