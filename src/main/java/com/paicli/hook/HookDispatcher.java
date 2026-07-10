package com.paicli.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 统一的生命周期 Hook 分发器：按事件维护一个有序租户列表，对一次分发组合出单一 {@link HookDecision}。
 *
 * 组合语义（ADR 0012，最严者胜）：
 * <ol>
 *   <li>按注册顺序（builtin → user → project）逐个求值；</li>
 *   <li>任一 {@code BLOCK} 立即短路——后续租户不再求值，返回带已聚合 context 的 BLOCK；</li>
 *   <li>非阻断中 {@code ASK} 压过 {@code ALLOW} 压过 {@code CONTINUE}（按 {@link HookDecision.Outcome} 的序取最大）；</li>
 *   <li>{@code additionalContext} 跨租户聚合。</li>
 * </ol>
 * 空注册表是**零成本 no-op**：没有对应事件的租户时直接返回 {@link HookDecision#cont()}，不分配、不遍历。
 *
 * wave 2 预留字段（{@code updatedInput} 等）若被租户填了，这里 warn-and-ignore。
 */
public class HookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(HookDispatcher.class);

    private final Map<HookEvent, List<HookTenant>> tenantsByEvent = new EnumMap<>(HookEvent.class);
    private final Map<HookTenant, Counts> counts = new java.util.concurrent.ConcurrentHashMap<>();

    /** 注册一个租户到某事件；调用顺序即求值顺序（builtin 先注册、user/project 后注册）。 */
    public void register(HookEvent event, HookTenant tenant) {
        if (event == null || tenant == null) {
            return;
        }
        tenantsByEvent.computeIfAbsent(event, e -> new ArrayList<>()).add(tenant);
    }

    public boolean hasTenants(HookEvent event) {
        List<HookTenant> tenants = tenantsByEvent.get(event);
        return tenants != null && !tenants.isEmpty();
    }

    /** 是否注册过任何事件的任何租户——Agent 据此决定要不要把分发器注入到工具注册表（空则不注入，保持零成本）。 */
    public boolean hasAnyTenants() {
        for (List<HookTenant> tenants : tenantsByEvent.values()) {
            if (tenants != null && !tenants.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public HookDecision dispatch(HookEvent event, HookPayload payload) {
        List<HookTenant> tenants = tenantsByEvent.get(event);
        if (tenants == null || tenants.isEmpty()) {
            return HookDecision.cont();  // 零成本 no-op
        }

        HookDecision.Outcome best = HookDecision.Outcome.CONTINUE;
        List<String> contexts = new ArrayList<>();
        for (HookTenant tenant : tenants) {
            HookDecision decision;
            try {
                decision = tenant.evaluate(payload);
            } catch (RuntimeException e) {
                // 内建租户异常 fail-open（视为 CONTINUE），不因单个租户崩掉主循环。
                log.warn("hook tenant '{}' threw during {} dispatch; treating as CONTINUE", tenant.name(), event, e);
                continue;
            }
            if (decision == null) {
                continue;
            }
            counts(tenant).fires.incrementAndGet();  // 求值即计一次 fire（供 /hooks 展示本会话活动）
            if (decision.hasReservedFields()) {
                log.warn("hook tenant '{}' set wave-2 reserved fields (updatedInput/updatedToolOutput/async); ignoring in wave 1",
                        tenant.name());
            }
            String context = decision.additionalContext();
            if (context != null && !context.isBlank()) {
                contexts.add(context.trim());
            }
            if (decision.isBlock()) {
                counts(tenant).blocks.incrementAndGet();
                // 最严短路：后续租户不再求值。
                return new HookDecision(HookDecision.Outcome.BLOCK, decision.reason(),
                        joinContexts(contexts), decision.statusMessage(), null, null, false);
            }
            if (decision.outcome().ordinal() > best.ordinal()) {
                best = decision.outcome();
            }
        }
        return new HookDecision(best, null, joinContexts(contexts), null, null, null, false);
    }

    /** 本会话已加载的 hook 清单（供 {@code /hooks} 检视）：事件 + 名称 + 来源 + matcher + fire/block 计数。 */
    public List<HookListing> listing() {
        List<HookListing> out = new ArrayList<>();
        for (Map.Entry<HookEvent, List<HookTenant>> entry : tenantsByEvent.entrySet()) {
            for (HookTenant tenant : entry.getValue()) {
                Counts c = counts.get(tenant);
                String matcher = tenant instanceof ShellHook sh && sh.matcher() != null
                        ? sh.matcher().pattern() : null;
                out.add(new HookListing(entry.getKey(), tenant.name(), tenant.source(), matcher,
                        c == null ? 0 : c.fires.get(), c == null ? 0 : c.blocks.get()));
            }
        }
        return out;
    }

    /** 一条 hook 清单项。 */
    public record HookListing(HookEvent event, String name, String source, String matcher,
                              long fires, long blocks) {}

    private Counts counts(HookTenant tenant) {
        return counts.computeIfAbsent(tenant, t -> new Counts());
    }

    private static final class Counts {
        final java.util.concurrent.atomic.AtomicLong fires = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong blocks = new java.util.concurrent.atomic.AtomicLong();
    }

    private static String joinContexts(List<String> contexts) {
        return contexts.isEmpty() ? null : String.join("\n\n", contexts);
    }
}
