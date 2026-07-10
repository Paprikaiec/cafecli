package com.paicli.hook;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seam 1（{@code AgentBudgetTest} 风格的纯单元 seam）：只断言 dispatcher 的外部组合行为——
 * 空注册表 no-op、注册顺序、BLOCK 短路、ASK &gt; ALLOW、context 聚合、预留字段 warn-ignore。
 */
class HookDispatcherTest {

    private static HookPayload stopPayload() {
        return HookPayload.forStop("s1", null, "/proj", List.of(), 0);
    }

    private static HookTenant tenant(String name, HookDecision decision) {
        return new HookTenant() {
            @Override public String name() { return name; }
            @Override public HookDecision evaluate(HookPayload payload) { return decision; }
        };
    }

    @Test
    void emptyRegistryIsContinueNoOp() {
        HookDispatcher dispatcher = new HookDispatcher();

        assertFalse(dispatcher.hasTenants(HookEvent.STOP));
        HookDecision decision = dispatcher.dispatch(HookEvent.STOP, stopPayload());

        assertEquals(HookDecision.Outcome.CONTINUE, decision.outcome());
        assertNull(decision.additionalContext());
    }

    @Test
    void singleContinueTenantStillContinues() {
        HookDispatcher dispatcher = new HookDispatcher();
        dispatcher.register(HookEvent.STOP, tenant("noop", HookDecision.cont()));

        assertEquals(HookDecision.Outcome.CONTINUE,
                dispatcher.dispatch(HookEvent.STOP, stopPayload()).outcome());
    }

    @Test
    void firstBlockShortCircuitsLaterTenants() {
        HookDispatcher dispatcher = new HookDispatcher();
        List<String> evaluated = new ArrayList<>();
        dispatcher.register(HookEvent.STOP, new HookTenant() {
            @Override public String name() { return "blocker"; }
            @Override public HookDecision evaluate(HookPayload p) {
                evaluated.add("blocker");
                return HookDecision.block("stop-a");
            }
        });
        dispatcher.register(HookEvent.STOP, new HookTenant() {
            @Override public String name() { return "after"; }
            @Override public HookDecision evaluate(HookPayload p) {
                evaluated.add("after");
                return HookDecision.ask();
            }
        });

        HookDecision decision = dispatcher.dispatch(HookEvent.STOP, stopPayload());

        assertEquals(HookDecision.Outcome.BLOCK, decision.outcome());
        assertEquals("stop-a", decision.reason());
        assertEquals(List.of("blocker"), evaluated, "BLOCK 应短路，后续租户不再求值");
    }

    @Test
    void askOutranksAllowRegardlessOfOrder() {
        HookDispatcher allowThenAsk = new HookDispatcher();
        allowThenAsk.register(HookEvent.PRE_TOOL_USE, tenant("allow", HookDecision.allow()));
        allowThenAsk.register(HookEvent.PRE_TOOL_USE, tenant("ask", HookDecision.ask()));
        assertEquals(HookDecision.Outcome.ASK,
                allowThenAsk.dispatch(HookEvent.PRE_TOOL_USE, stopPayload()).outcome());

        HookDispatcher askThenAllow = new HookDispatcher();
        askThenAllow.register(HookEvent.PRE_TOOL_USE, tenant("ask", HookDecision.ask()));
        askThenAllow.register(HookEvent.PRE_TOOL_USE, tenant("allow", HookDecision.allow()));
        assertEquals(HookDecision.Outcome.ASK,
                askThenAllow.dispatch(HookEvent.PRE_TOOL_USE, stopPayload()).outcome());
    }

    @Test
    void additionalContextAggregatesAcrossTenants() {
        HookDispatcher dispatcher = new HookDispatcher();
        dispatcher.register(HookEvent.STOP, tenant("a", HookDecision.cont().withAdditionalContext("ctx-a")));
        dispatcher.register(HookEvent.STOP, tenant("b", HookDecision.cont().withAdditionalContext("ctx-b")));

        HookDecision decision = dispatcher.dispatch(HookEvent.STOP, stopPayload());

        assertEquals(HookDecision.Outcome.CONTINUE, decision.outcome());
        assertTrue(decision.additionalContext().contains("ctx-a"));
        assertTrue(decision.additionalContext().contains("ctx-b"));
    }

    @Test
    void registrationOrderIsEvaluationOrder() {
        HookDispatcher dispatcher = new HookDispatcher();
        List<String> order = new ArrayList<>();
        dispatcher.register(HookEvent.STOP, new HookTenant() {
            @Override public String name() { return "builtin"; }
            @Override public HookDecision evaluate(HookPayload p) { order.add("builtin"); return HookDecision.cont(); }
        });
        dispatcher.register(HookEvent.STOP, new HookTenant() {
            @Override public String name() { return "user"; }
            @Override public HookDecision evaluate(HookPayload p) { order.add("user"); return HookDecision.cont(); }
        });

        dispatcher.dispatch(HookEvent.STOP, stopPayload());

        assertEquals(List.of("builtin", "user"), order);
    }

    @Test
    void reservedWaveTwoFieldsAreWarnedAndIgnored() {
        HookDispatcher dispatcher = new HookDispatcher();
        HookDecision withReserved = new HookDecision(HookDecision.Outcome.CONTINUE,
                null, "ctx", null, "rewritten-input", null, true);
        dispatcher.register(HookEvent.PRE_TOOL_USE, tenant("reserving", withReserved));

        HookDecision composed = dispatcher.dispatch(HookEvent.PRE_TOOL_USE, stopPayload());

        assertEquals(HookDecision.Outcome.CONTINUE, composed.outcome());
        assertFalse(composed.hasReservedFields(), "预留字段不应进入组合结果");
        assertEquals("ctx", composed.additionalContext());
    }
}
