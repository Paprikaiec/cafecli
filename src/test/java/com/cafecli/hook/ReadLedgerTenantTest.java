package com.cafecli.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 读前写台账（Read-before-Write Ledger）租户的单元 seam。用真实临时文件（@TempDir）驱动语义：
 * 盲写阻断 / 读后写放行 / 读后被外部改动写视为陈旧阻断 / 新文件写放行 / 自己成功写入刷新台账 /
 * 台账按 agent 作用域隔离（子任务的读不满足主 agent、反之亦然）。
 *
 * 断言外部决定（BLOCK/CONTINUE），不碰台账内部状态。
 */
class ReadLedgerTenantTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HookPayload pre(String tool, String root, String path, String taskId) {
        return HookPayload.forPreToolUse("s1", taskId, root, tool, pathArg(path));
    }

    private static HookPayload post(String tool, String root, String path, String taskId) {
        return HookPayload.forPostToolUse("s1", taskId, root, tool, pathArg(path), "ok");
    }

    private static com.fasterxml.jackson.databind.JsonNode pathArg(String path) {
        return MAPPER.createObjectNode().put("path", path);
    }

    @Test
    void blindWriteToExistingUnreadFileBlocks(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Foo.java"), "class Foo {}");
        ReadLedgerTenant ledger = new ReadLedgerTenant();

        HookDecision decision = ledger.evaluate(pre("write_file", root.toString(), "Foo.java", null));

        assertEquals(HookDecision.Outcome.BLOCK, decision.outcome());
        assertNotNull(decision.reason());
    }

    @Test
    void writeAfterReadPasses(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Foo.java"), "class Foo {}");
        ReadLedgerTenant ledger = new ReadLedgerTenant();

        ledger.evaluate(post("read_file", root.toString(), "Foo.java", null));
        HookDecision decision = ledger.evaluate(pre("edit_file", root.toString(), "Foo.java", null));

        assertEquals(HookDecision.Outcome.CONTINUE, decision.outcome());
    }

    @Test
    void externalModificationBetweenReadAndWriteBlocksAsStale(@TempDir Path root) throws Exception {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "class Foo {}");
        ReadLedgerTenant ledger = new ReadLedgerTenant();

        ledger.evaluate(post("read_file", root.toString(), "Foo.java", null));
        Files.writeString(file, "class Foo { int x; }");  // 外部改动

        HookDecision decision = ledger.evaluate(pre("edit_file", root.toString(), "Foo.java", null));

        assertEquals(HookDecision.Outcome.BLOCK, decision.outcome());
    }

    @Test
    void newFileWritePasses(@TempDir Path root) {
        ReadLedgerTenant ledger = new ReadLedgerTenant();

        HookDecision decision = ledger.evaluate(pre("write_file", root.toString(), "New.java", null));

        assertEquals(HookDecision.Outcome.CONTINUE, decision.outcome());
    }

    @Test
    void ownSuccessfulWriteRefreshesSoNextEditPasses(@TempDir Path root) throws Exception {
        Path file = root.resolve("Foo.java");
        Files.writeString(file, "class Foo {}");
        ReadLedgerTenant ledger = new ReadLedgerTenant();

        ledger.evaluate(post("read_file", root.toString(), "Foo.java", null));
        // agent 自己写入并落盘后，PostToolUse 刷新台账
        Files.writeString(file, "class Foo { int a; }");
        ledger.evaluate(post("write_file", root.toString(), "Foo.java", null));

        // 连续第二次编辑同一文件：无需重复 re-read
        HookDecision decision = ledger.evaluate(pre("edit_file", root.toString(), "Foo.java", null));

        assertEquals(HookDecision.Outcome.CONTINUE, decision.outcome());
    }

    @Test
    void ledgerIsPerAgentScope(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Foo.java"), "class Foo {}");
        ReadLedgerTenant ledger = new ReadLedgerTenant();

        // 主 agent（taskId=null）读过
        ledger.evaluate(post("read_file", root.toString(), "Foo.java", null));

        // 子任务 task_1 没读过 —— 它的写仍被阻断
        HookDecision childDecision = ledger.evaluate(pre("write_file", root.toString(), "Foo.java", "task_1"));
        assertEquals(HookDecision.Outcome.BLOCK, childDecision.outcome());

        // 主 agent 的写放行（它读过）
        HookDecision mainDecision = ledger.evaluate(pre("write_file", root.toString(), "Foo.java", null));
        assertEquals(HookDecision.Outcome.CONTINUE, mainDecision.outcome());
    }

    @Test
    void nonWriteToolPassesAtPreToolUse(@TempDir Path root) {
        ReadLedgerTenant ledger = new ReadLedgerTenant();

        HookDecision decision = ledger.evaluate(pre("execute_command", root.toString(), null, null));

        assertEquals(HookDecision.Outcome.CONTINUE, decision.outcome());
    }

    @Test
    void enabledBySystemPropertyDefaultsOff() {
        String prev = System.getProperty(HookProperties.READ_LEDGER_ENABLED);
        System.clearProperty(HookProperties.READ_LEDGER_ENABLED);
        try {
            assertEquals(false, ReadLedgerTenant.enabledBySystemProperty());
        } finally {
            if (prev != null) {
                System.setProperty(HookProperties.READ_LEDGER_ENABLED, prev);
            }
        }
    }
}
