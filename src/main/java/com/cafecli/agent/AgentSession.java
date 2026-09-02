package com.cafecli.agent;

import com.cafecli.agent.task.TaskRegistry;
import com.cafecli.llm.LlmClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 会话内异步任务的事件循环 seam（ADR 0005），从 {@code Main} 的交互 REPL 抽出、无终端依赖、可桩驱动。
 *
 * <p>模型：主线程退化为输入泵（在 {@code Main} 里，常驻 readLine），把用户输入投成 {@link UserSubmit}
 * 事件；后台任务完成投成 {@link TaskDone} 事件。本类持有<b>单一串行执行器</b>线程，它是主 Agent
 * 对话历史的<b>唯一写入者</b>：从事件队列取事件，在<b>回合边界</b>把当前所有待处理事件 drain 进同一个
 * 回合（多个任务同时完成 → 一个回合看到全部），跑一个主 Agent 回合，把输出交给 push sink（在 {@code Main}
 * 里经 {@code LineReader#printAbove} 落到输入行上方；在测试里被捕获断言）。
 *
 * <p>JLine 输入泵与 printAbove 渲染留在 {@code Main}、在本 seam 之上，仅手测；本类的行为全部可用桩
 * {@link LlmClient} 在无终端下驱动与断言。
 */
public final class AgentSession {

    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);

    /** 事件循环上的项：用户提交 / 后台任务完成。 */
    public sealed interface Event permits UserSubmit, TaskDone {}

    /** 用户在提示符提交的一条输入。 */
    public record UserSubmit(String input) implements Event {}

    /** 一个后台任务完成，携带要回灌主对话的 result-only 通知文本。 */
    public record TaskDone(String taskId, String notification) implements Event {}

    private final Agent mainAgent;
    private final TaskRegistry taskRegistry;
    private final Consumer<String> pushSink;
    private volatile Runnable autonomousTurnStarter = () -> { };
    // 「忙 ⇄ 闲」翻转的通知。主线程常驻 readLine（ADR 0014），所以这两个边缘都发生在**别的**线程上；
    // 没有它，[[输入框]]的右提示符要等用户下一次按键才跟上会话的状态。
    private volatile Runnable activityListener = () -> { };
    // 可插拔回合执行器：默认 mainAgent.run；Main 注入以包裹快照锚定 + 状态栏更新等终端侧逻辑。
    private volatile Function<String, String> turnRunner;

    private final BlockingQueue<Event> queue = new LinkedBlockingQueue<>();
    private final Object idleLock = new Object();
    private int pending = 0;
    private volatile boolean running = false;
    private Thread executor;

    public AgentSession(Agent mainAgent, TaskRegistry taskRegistry, Consumer<String> pushSink) {
        this.mainAgent = mainAgent;
        this.taskRegistry = taskRegistry;
        this.pushSink = pushSink == null ? s -> {} : pushSink;
    }

    public Agent mainAgent() {
        return mainAgent;
    }

    public TaskRegistry taskRegistry() {
        return taskRegistry;
    }

    /**
     * 注入回合执行器（输入 → 输出）：在执行器线程上把每个主回合包进快照锚定 + 状态栏更新等终端侧逻辑。
     * 默认（未注入）为直接 {@code mainAgent.run}。前台回合与 push 自治回合都走它，快照锚定的是串行主回合边界。
     */
    public void setTurnRunner(Function<String, String> turnRunner) {
        this.turnRunner = turnRunner;
    }

    /**
     * 每当「有没有活在跑」这件事翻转时被调用一次（投递事件时、以及队列跑空时）。
     *
     * <p>在**执行器 / 投递者线程**上调用，且在任何锁之外：它的用途是让输入侧重排一次输入框，
     * 而输入侧正坐在 readLine 里。监听器抛异常不影响回合。
     */
    public void setActivityListener(Runnable activityListener) {
        this.activityListener = activityListener == null ? () -> { } : activityListener;
    }

    private void notifyActivityChanged() {
        try {
            activityListener.run();
        } catch (RuntimeException e) {
            log.debug("activity listener failed", e);
        }
    }

    /** Start renderer-owned turn state for TaskDone-only autonomous turns. */
    public void setAutonomousTurnStarter(Runnable autonomousTurnStarter) {
        this.autonomousTurnStarter = autonomousTurnStarter == null ? () -> { } : autonomousTurnStarter;
    }

    /** 非阻塞：事件队列已清空且无回合在处理（供 Main 输入泵在前台回合期间泵审批时判定本轮是否跑完）。 */
    public boolean isIdle() {
        synchronized (idleLock) {
            return pending == 0;
        }
    }

    /** 启动串行执行器线程（主对话历史的唯一写入者）。 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        executor = new Thread(this::loop, "agent-session-executor");
        executor.setDaemon(true);
        executor.start();
    }

    public void stop() {
        running = false;
        if (executor != null) {
            executor.interrupt();
        }
        synchronized (idleLock) {
            idleLock.notifyAll();
        }
    }

    /** 投递一个事件（线程安全）：用户提交或后台任务完成都走这里。 */
    public void postEvent(Event event) {
        if (event == null) {
            return;
        }
        synchronized (idleLock) {
            pending++;
        }
        queue.add(event);
        notifyActivityChanged();
    }

    /** 投递一个后台任务完成事件——可直接作为派生器的后台完成回调（{@code BackgroundCompletionSink}）。 */
    public void onBackgroundTaskDone(String taskId, String notification) {
        postEvent(new TaskDone(taskId, notification));
    }

    /**
     * 把一条[[转向消息]]交给**正在跑的**回合（issue 10）。返回 false 表示此刻没有回合能接它，调用方
     * 应改走普通提交（{@code postEvent(UserSubmit)}）。
     *
     * <p>「有没有回合在跑」与「把消息放进去」必须是同一个原子动作，否则回合可能恰好在两者之间结束、
     * 消息落进一个再也没人 drain 的槽里。这条判断因此不读 {@link #isIdle()}，而由
     * {@link SteeringInbox#offer} 在它自己的锁下一并回答。
     */
    public boolean steer(String message) {
        return mainAgent != null && mainAgent.steering().offer(message);
    }

    /** 取消回合时丢弃尚未生效的[[转向消息]]（ESC / Ctrl+C，[[取消键]]表）。 */
    public void discardSteering() {
        if (mainAgent != null) {
            mainAgent.steering().discard();
        }
    }

    /** 阻塞直到所有已投递事件都被处理完（执行器空闲）；超时返回 false。测试用于确定性断言。 */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadlineMs = System.currentTimeMillis() + timeout.toMillis();
        synchronized (idleLock) {
            while (pending > 0) {
                long remain = deadlineMs - System.currentTimeMillis();
                if (remain <= 0) {
                    return false;
                }
                idleLock.wait(remain);
            }
            return true;
        }
    }

    private void loop() {
        while (running) {
            Event first;
            try {
                first = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            // 回合边界：把当前所有待处理事件 drain 进同一个回合。
            List<Event> batch = new ArrayList<>();
            batch.add(first);
            queue.drainTo(batch);
            try {
                runTurn(batch);
            } catch (Throwable t) {
                log.warn("agent session turn failed", t);
            } finally {
                boolean idle;
                synchronized (idleLock) {
                    pending -= batch.size();
                    if (pending <= 0) {
                        pending = 0;
                        idleLock.notifyAll();
                    }
                    idle = pending == 0;
                }
                if (idle) {
                    notifyActivityChanged();
                }
            }
        }
    }

    /**
     * 跑一个回合：把本批事件合成一次主 Agent 输入——后台任务完成以带标签的 {@code <task-notification>}
     * user 角色块前置（result-only，「像 user 但非真实用户」的信号），用户提交的原文追加在后。
     * 主 Agent 在此回合写主对话历史（唯一写入者），输出交给 push sink。
     */
    private void runTurn(List<Event> batch) {
        StringBuilder input = new StringBuilder();
        boolean hasUserSubmit = false;
        for (Event event : batch) {
            if (event instanceof TaskDone done) {
                input.append("<task-notification task_id=\"").append(done.taskId()).append("\">\n")
                        .append(done.notification() == null ? "" : done.notification())
                        .append("\n</task-notification>\n\n");
            }
        }
        for (Event event : batch) {
            if (event instanceof UserSubmit submit
                    && submit.input() != null && !submit.input().isBlank()) {
                hasUserSubmit = true;
                input.append(submit.input()).append('\n');
            }
        }
        String turnInput = input.toString().trim();
        if (turnInput.isEmpty()) {
            return;
        }
        if (!hasUserSubmit) {
            autonomousTurnStarter.run();
        }
        Function<String, String> runner = turnRunner;
        // 回合边界就是[[转向消息]]槽的开合边界：槽在这里打开，回合中提交的内容由 ReAct 循环在迭代
        // 边界 drain；回合结束时槽里若还剩东西，说明它没等到下一个迭代边界（模型直接给了最终答案），
        // 必须重新投递为普通提交而不是静默丢弃——用户已经看过回显，以为它被接受了。
        SteeringInbox steering = mainAgent == null ? null : mainAgent.steering();
        if (steering != null) {
            steering.openForTurn();
        }
        String runResult;
        try {
            runResult = runner != null ? runner.apply(turnInput) : mainAgent.run(turnInput);
        } finally {
            // In the finally, so a turn that failed still hands the user's own words back rather than
            // taking them down with it.
            if (steering != null) {
                steering.closeForTurn().forEach(message -> postEvent(new UserSubmit(message)));
            }
        }
        pushSink.accept(finalAssistantText(runResult));
    }

    /** 主 Agent.run 在已流式输出时返回空串；此时回到主对话历史取最后一条 assistant 文本兜底。 */
    private String finalAssistantText(String runResult) {
        if (runResult != null && !runResult.isBlank()) {
            return runResult;
        }
        List<LlmClient.Message> history = mainAgent.getConversationHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            LlmClient.Message m = history.get(i);
            if ("assistant".equals(m.role()) && m.content() != null && !m.content().isBlank()
                    && (m.toolCalls() == null || m.toolCalls().isEmpty())) {
                return m.content();
            }
        }
        return runResult == null ? "" : runResult;
    }
}
