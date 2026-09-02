package com.cafecli.cli;

import com.cafecli.agent.AgentSession;
import com.cafecli.hitl.ApprovalPump;
import com.cafecli.render.Renderer;
import com.cafecli.runtime.CancellationRegistry;
import com.cafecli.util.AnsiStyle;

import org.jline.terminal.Terminal;

/**
 * [[取消键]]表里那条一句话规则的**唯一实现**——**有回合在跑就停回合**——外加它的会话级 SIGINT
 * 兜底（ADR 0016 D3）。
 *
 * <p>三个入口都落到这里，因为它们说的是同一件事：常驻行编辑器上的 ESC widget、{@code readLine}
 * 抛出的 {@code UserInterruptException}（Ctrl+C），以及模态自持读取时由模态认下的那个中断。写成
 * 三份的代价不是重复代码，而是**三份会分头漂移的取消范围**——这正是本 issue 之前的状态（ESC 拒待决
 * [[审批应答]]、Ctrl+C 不拒）。
 *
 * <p>兜底那一半的成因是两条事实叠在一起：{@code enterRawMode()} 不清 {@code ISIG}，所以每个 raw-mode
 * 模态里 Ctrl+C 照常产生 SIGINT；而本仓库除 WINCH 外从未安装过任何信号处理器，所以 SIGINT 落到 JVM
 * 默认处理器。JLine 只在 {@code readLine} 期间装它自己的 INT handler（{@code LineReaderImpl} 保存前
 * 一个并在 {@code finally} 里还原），出了 {@code readLine} 就是裸的。**行编辑器常驻之后**（issue 07）
 * 这个兜底处处被盖住，实际服务的只剩装配期与斜杠 palette、项目 hook 信任确认这类仍在 {@code readLine}
 * 之外的读取；跑在 {@code readLine} **里面**的模态（[[转写浏览层]]是个 widget）只能自己认下中断，再
 * 调回这里。
 *
 * <p>规则**不带条件分支**：
 * <ul>
 *   <li>有前台回合在跑 → 取消它与仍[[附着]]的任务、拒掉待决的[[审批应答]]、丢弃尚未生效的
 *       [[转向消息]]，并给出即时反馈（[[游离]]任务不受影响，仍只能经 {@code /task cancel} 终止）；</li>
 *   <li>没有可停的东西 → <b>什么都不做</b>。</li>
 * </ul>
 *
 * <p><b>不关模态</b>：把屏幕从一个正在读的人手里拿走需要它自己的理由——一条待决[[审批应答]]有
 * （它真的需要主屏上的[[输入框]]，ADR 0010 修订 A14），一次取消没有。反馈经 {@link Renderer#stream()}
 * 进有序提交日志，[[转写浏览层]]的实时跟随会把它显示出来，所以「回合停下来了」这件事在层里看得见。
 *
 * <p><b>signal 线程只说话，不写终端</b>：{@code stream()} 的行缓冲输出交给[[活区]]的冻结队列，由
 * 渲染线程在下一帧写出。信号处理器因此不与任何人抢终端，也从不读终端。
 *
 * <p><b>明码标价的代价</b>：装了这个 handler 之后 Ctrl+C 不再是卡死进程的逃生口（MCP 启动卡住、
 * 工具卡死这类没有回合可停的场合，它会静默无事）。「空闲时改回默认处理器」的变体被否决过——那让
 * 规则带上条件分支。剩余逃生口是 {@code Ctrl+\}（SIGQUIT）、从另一个终端 {@code kill}，以及
 * [[取消键]]表里「空闲且框为空时连按两次 Ctrl+C」那一格。
 */
public final class SessionInterruptGuard {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SessionInterruptGuard.class);

    /** 拒绝待决审批时交给请求方的原因——它会作为工具结果回到模型那里。 */
    private static final String REASON = "用户取消了当前回合";

    private final CancellationRegistry cancellations;
    private final Renderer renderer;
    private final ApprovalPump approvals;
    private final AgentSession session;

    public SessionInterruptGuard(CancellationRegistry cancellations, Renderer renderer,
                                 ApprovalPump approvals, AgentSession session) {
        this.cancellations = cancellations;
        this.renderer = renderer;
        this.approvals = approvals;
        this.session = session;
    }

    /**
     * 在装配期挂到终端的 INT 信号上。
     *
     * <p>这一步同时是「SIGINT 不再落到 JVM 默认处理器」的**机制**：对系统终端，JLine 的
     * {@code PosixSysTerminal.handle} 在装非默认处理器时会注册一个转发到 {@code raise(INT)} 的
     * JVM 级信号处理器。{@code readLine} 期间 JLine 会用自己的覆盖它、退出时还原本处理器，所以
     * 空闲态行为一字不改（仍抛 {@code UserInterruptException}）。
     */
    public void installOn(Terminal terminal) {
        if (terminal == null) {
            return;
        }
        terminal.handle(Terminal.Signal.INT, signal -> interrupt());
    }

    /**
     * 有没有东西可停——那条一句话规则读的就是它。
     *
     * <p>两个来源都算数，且**不能只读一个**：注册表的前台令牌是回合真正在跑的证据，而事件循环的
     * 忙闲在提交之后、令牌建立之前就已经翻了。只读令牌，用户在这段窗口里按下的 Ctrl+C 会被当成
     * 「没有可停的东西」，于是[[取消键]]表里的「连按两次退出」会把会话退掉。
     */
    public boolean turnIsRunning() {
        return (cancellations != null && cancellations.foregroundTurnToken() != null)
                || (session != null && !session.isIdle());
    }

    /**
     * 一次取消。由信号线程调用，也由输入线程的两条按键通路调用。
     *
     * <p>取消是协作式的：回合在边界才停（流式回答不可中途打断），所以这里立刻给一句反馈。**拒掉
     * 待决审批是这条通路的一部分而不是附加动作**——一个 park 在审批 future 上的 worker 不会因为令牌
     * 被取消而醒过来，不放掉它，「回合停下来」就落不到实处。
     *
     * <p><b>先取消、再说话，且说话失败不抵消已经做成的事</b>：JLine 的信号代理不接 handler 抛出的
     * 异常，JDK 的信号分发线程也不接，于是一个漏出去的异常会被默认处理器把栈打到 stderr——正好违反
     * 「signal 线程从不直写终端」这条不变式，而此刻屏幕上可能开着[[转写浏览层]]。
     *
     * @return 是否真的停下了什么。调用方据此决定接下来做什么（[[取消键]]表里 Ctrl+C 的另外三格）。
     */
    public boolean interrupt() {
        if (!turnIsRunning()) {
            // 没有可停的东西：什么都不做。关键是不死。
            return false;
        }
        int rejected = approvals == null ? 0 : approvals.rejectAllPending(REASON);
        // 尚未生效的[[转向消息]]随回合一起丢弃（issue 10）：它们瞄准的是这个回合，带进用户下一句话
        // 就成了挂在新请求上的陈旧指令。
        if (session != null) {
            session.discardSteering();
        }
        int cancelledTasks = cancellations == null ? 0 : cancellations.cancelForegroundAndAttached();
        log.info("cancel: foreground turn + {} attached task(s); rejected {} pending approval(s)",
                cancelledTasks, rejected);
        try {
            renderer.stream().println(AnsiStyle.subtle("⏹️ 已请求取消（当前 LLM 步骤完成后停止）"));
        } catch (RuntimeException e) {
            log.debug("cancellation feedback could not be written", e);
        }
        return true;
    }
}
