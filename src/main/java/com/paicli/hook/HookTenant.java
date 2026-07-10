package com.paicli.hook;

/**
 * 挂在 {@link HookDispatcher} 上的一个具体 hook 实现（[[Hook 租户]]）。
 *
 * 内建租户（进程内、可访问会话状态）与用户配置的 shell 命令租户（后续 issue）同权、共用这一个契约。
 * 实现应是无副作用的纯判定：读 {@link HookPayload}，给出 {@link HookDecision}。
 */
public interface HookTenant {

    /** 租户标识，用于审计与 {@code /hooks} 展示。 */
    String name();

    /** 对一次分发给出决定；无意见返回 {@link HookDecision#cont()}。 */
    HookDecision evaluate(HookPayload payload);

    /** 来源（供 {@code /hooks} 展示）：内建租户默认 {@code builtin}；shell 租户覆写为 {@code user}/{@code project}。 */
    default String source() {
        return "builtin";
    }
}
