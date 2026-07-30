package com.cafecli.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 取消令牌注册表（ADR 0005 取消语义）：显式持有当前前台主回合令牌与每任务令牌，取代
 * {@link CancellationContext} 的 {@code InheritableThreadLocal} 继承——每个任务显式登记自己的令牌，
 * 不再靠线程继承（虚拟线程 + 跨执行器派生下继承会串错）。
 *
 * <p>语义：
 * <ul>
 *   <li><b>ESC</b> = 广播取消「眼前这件事」：当前前台主回合 + 所有仍<b>附着</b>（attached，有前台回合在
 *       同步等它）的任务；<b>游离</b>（detached，显式后台派生或前台超时转后台）的任务<b>不受影响</b>。</li>
 *   <li><b>/task cancel &lt;id&gt;</b> = 精确取消单个任务令牌（线程中断在集成层做）。</li>
 * </ul>
 *
 * <p>纯策略对象：只做令牌与附着/游离状态的会计与取消决策，不持有线程；可在无线程环境下断言「恰好
 * 取消了哪些令牌」（类比 AdmissionControlTest / AgentBudgetTest）。
 */
public final class CancellationRegistry {

    /** 当前前台主回合令牌；无前台回合时为 null。 */
    private final AtomicReference<CancellationToken> foregroundTurn = new AtomicReference<>();
    private final Map<String, TaskCancellation> tasks = new ConcurrentHashMap<>();

    private record TaskCancellation(CancellationToken token, boolean attached) {}

    /** 开一个前台主回合：换一个新令牌并设为当前，返回它。 */
    public CancellationToken beginForegroundTurn() {
        CancellationToken token = new CancellationToken();
        foregroundTurn.set(token);
        return token;
    }

    /** 结束前台主回合（仅当它仍是当前回合时清空，避免清掉已开始的下一回合）。 */
    public void endForegroundTurn(CancellationToken token) {
        foregroundTurn.compareAndSet(token, null);
    }

    public CancellationToken foregroundTurnToken() {
        return foregroundTurn.get();
    }

    /**
     * 登记一个任务令牌。{@code attached=true} 表示有前台回合在同步等它（前台派生）；
     * {@code attached=false} 表示游离（显式后台派生）。返回该任务的令牌。
     */
    public CancellationToken registerTask(String taskId, boolean attached) {
        CancellationToken token = new CancellationToken();
        tasks.put(taskId, new TaskCancellation(token, attached));
        return token;
    }

    /** 任务转游离（前台超时 auto-background，或显式后台）：此后 ESC 不再波及它。 */
    public void detach(String taskId) {
        tasks.computeIfPresent(taskId, (id, tc) -> new TaskCancellation(tc.token(), false));
    }

    /** 任务结束 / 不再追踪：移除其令牌。 */
    public void unregisterTask(String taskId) {
        tasks.remove(taskId);
    }

    public CancellationToken taskToken(String taskId) {
        TaskCancellation tc = tasks.get(taskId);
        return tc == null ? null : tc.token();
    }

    public boolean isAttached(String taskId) {
        TaskCancellation tc = tasks.get(taskId);
        return tc != null && tc.attached();
    }

    /**
     * ESC：取消当前前台主回合 + 所有仍附着的任务；游离任务不受影响。
     * 返回被取消的任务 id 数（便于断言 / 日志）。
     */
    public int cancelForegroundAndAttached() {
        CancellationToken fg = foregroundTurn.get();
        if (fg != null) {
            fg.cancel();
        }
        int cancelled = 0;
        for (TaskCancellation tc : tasks.values()) {
            if (tc.attached()) {
                tc.token().cancel();
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * /task cancel &lt;id&gt;：精确取消单个任务令牌（无论附着/游离）。
     * 返回 true 表示找到并取消；false 表示该 id 未在追踪（未知/已结束）。线程中断在集成层做。
     */
    public boolean cancelTask(String taskId) {
        TaskCancellation tc = tasks.get(taskId);
        if (tc == null) {
            return false;
        }
        tc.token().cancel();
        return true;
    }
}
