package com.paicli.hook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 收尾门租户的纯单元 seam（determinism：只喂 payload，不跑子进程）。
 * 断言外部决定：只读回合静默、写后已验证静默、写后无验证阻断、达阻断上限放行、验证在写之前不算数、正则可配置。
 */
class FinishGateTenantTest {

    private static final Pattern MVN = Pattern.compile(FinishGateTenant.DEFAULT_VERIFY_REGEX);

    private static HookPayload stop(List<HookPayload.TurnTool> tools, int blockCount) {
        return HookPayload.forStop("s1", null, "/proj", tools, blockCount);
    }

    private static HookPayload.TurnTool write(String path) {
        return new HookPayload.TurnTool("write_file", path, true);
    }

    private static HookPayload.TurnTool run(String command, boolean success) {
        return new HookPayload.TurnTool("execute_command", command, success);
    }

    @Test
    void readOnlyTurnStaysSilent() {
        FinishGateTenant gate = new FinishGateTenant(MVN, 2);
        HookPayload payload = stop(List.of(
                new HookPayload.TurnTool("read_file", "Foo.java", true)), 0);

        assertEquals(HookDecision.Outcome.CONTINUE, gate.evaluate(payload).outcome());
    }

    @Test
    void emptyTurnStaysSilent() {
        FinishGateTenant gate = new FinishGateTenant(MVN, 2);
        assertEquals(HookDecision.Outcome.CONTINUE, gate.evaluate(stop(List.of(), 0)).outcome());
    }

    @Test
    void writeWithoutVerificationBlocks() {
        FinishGateTenant gate = new FinishGateTenant(MVN, 2);
        HookDecision decision = gate.evaluate(stop(List.of(write("Foo.java")), 0));

        assertEquals(HookDecision.Outcome.BLOCK, decision.outcome());
    }

    @Test
    void verificationAfterWriteStaysSilent() {
        FinishGateTenant gate = new FinishGateTenant(MVN, 2);
        HookPayload payload = stop(List.of(
                write("Foo.java"),
                run("mvn -q test", true)), 0);

        assertEquals(HookDecision.Outcome.CONTINUE, gate.evaluate(payload).outcome());
    }

    @Test
    void verificationBeforeLastWriteStillBlocks() {
        FinishGateTenant gate = new FinishGateTenant(MVN, 2);
        HookPayload payload = stop(List.of(
                run("mvn -q test", true),
                write("Foo.java")), 0);

        assertEquals(HookDecision.Outcome.BLOCK, gate.evaluate(payload).outcome());
    }

    @Test
    void failedVerificationCommandDoesNotCountAsVerified() {
        FinishGateTenant gate = new FinishGateTenant(MVN, 2);
        HookPayload payload = stop(List.of(
                write("Foo.java"),
                run("mvn -q test", false)), 0);

        assertEquals(HookDecision.Outcome.BLOCK, gate.evaluate(payload).outcome());
    }

    @Test
    void blockCapReachedAllowsFinish() {
        FinishGateTenant gate = new FinishGateTenant(MVN, 2);
        HookPayload payload = stop(List.of(write("Foo.java")), 2);

        assertEquals(HookDecision.Outcome.CONTINUE, gate.evaluate(payload).outcome());
    }

    @Test
    void verifyRegexIsConfigurable() {
        // 自定义正则只认 "just check"，标准 mvn 命令因此不算验证。
        FinishGateTenant gate = new FinishGateTenant(Pattern.compile("just check"), 2);
        HookPayload mvnPayload = stop(List.of(write("Foo.java"), run("mvn test", true)), 0);
        assertEquals(HookDecision.Outcome.BLOCK, gate.evaluate(mvnPayload).outcome());

        HookPayload customPayload = stop(List.of(write("Foo.java"), run("just check", true)), 0);
        assertEquals(HookDecision.Outcome.CONTINUE, gate.evaluate(customPayload).outcome());
    }
}
