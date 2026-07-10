package com.paicli.hook;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 读前写台账（Read-before-Write Ledger）：{@code PreToolUse} + {@code PostToolUse} 两点的内建租户。
 *
 * 目的：拦住两种自伤写法——**盲写**（没读过就写已存在的文件）与**陈旧写**（读之后文件已被改动，
 * 仍照旧覆盖）。二者是 SWE-bench-Live 上 edit_file 匹配失败 / 整文件覆盖的主因。
 *
 * 语义：
 * <ul>
 *   <li>{@code PostToolUse} + read_file 成功：记录 path + 观测到的文件指纹（内容哈希）。</li>
 *   <li>{@code PreToolUse} + 写类工具：目标不存在→放行（新建）；存在但没读过→BLOCK「先读」；
 *       读过但文件已变→BLOCK「陈旧，重读」；读过且未变→放行。</li>
 *   <li>{@code PostToolUse} + 写类工具成功：用写入后的新指纹刷新台账，连续编辑同一文件无需重复 re-read。</li>
 * </ul>
 * 作用域**按 agent**：台账用 {@link HookPayload#taskId()}（null=主 Agent）做外层键。分发器虽在父子 registry
 * 间共享同一租户实例，但主 Agent 与子任务各有独立台账，互不满足。
 *
 * 守卫不受影响：台账阻断发生在 hook 层，PathGuard/CommandGuard 仍在执行时照常运行。
 * 默认关（{@code -Dpaicli.hook.read_ledger.enabled}，safe-edit 先例），评测开启。
 */
public class ReadLedgerTenant implements HookTenant {

    static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file");
    static final String READ_TOOL = "read_file";

    /** agentKey（taskId，null→"main"）→（绝对路径 → 观测指纹）。 */
    private final Map<String, Map<String, String>> ledgerByAgent = new ConcurrentHashMap<>();

    public static boolean enabledBySystemProperty() {
        return HookProperties.flag(HookProperties.READ_LEDGER_ENABLED);
    }

    @Override
    public String name() {
        return "read-ledger";
    }

    @Override
    public HookDecision evaluate(HookPayload payload) {
        if (payload == null) {
            return HookDecision.cont();
        }
        String tool = payload.toolName();
        if (tool == null) {
            return HookDecision.cont();
        }
        String path = extractPath(payload.toolInput());
        if (path == null || path.isBlank()) {
            return HookDecision.cont();
        }
        Path resolved = resolve(payload.projectRoot(), path);
        if (resolved == null) {
            return HookDecision.cont();
        }
        Map<String, String> ledger = ledgerByAgent.computeIfAbsent(
                agentKey(payload.taskId()), k -> new ConcurrentHashMap<>());
        String key = resolved.toString();

        if (payload.event() == HookEvent.PRE_TOOL_USE && WRITE_TOOLS.contains(tool)) {
            return evaluateWrite(ledger, key, resolved, path);
        }
        if (payload.event() == HookEvent.POST_TOOL_USE) {
            recordObservation(ledger, key, resolved, tool);
        }
        return HookDecision.cont();
    }

    private HookDecision evaluateWrite(Map<String, String> ledger, String key, Path resolved, String path) {
        if (!Files.exists(resolved)) {
            return HookDecision.cont();  // 新建文件：无需先读
        }
        String recorded = ledger.get(key);
        if (recorded == null) {
            return HookDecision.block(
                    "你正要写入 " + path + "，但本回合还没读过它。请先用 read_file 读取当前内容，"
                            + "确认改动基于真实文件后再写，避免盲写覆盖。");
        }
        String current = fingerprint(resolved);
        if (current != null && !recorded.equals(current)) {
            return HookDecision.block(
                    path + " 自你上次读取后已发生变化。请重新用 read_file 读取最新内容再写，"
                            + "避免覆盖并发改动（陈旧写）。");
        }
        return HookDecision.cont();
    }

    private void recordObservation(Map<String, String> ledger, String key, Path resolved, String tool) {
        if (!READ_TOOL.equals(tool) && !WRITE_TOOLS.contains(tool)) {
            return;
        }
        String fingerprint = fingerprint(resolved);
        if (fingerprint != null) {
            ledger.put(key, fingerprint);  // read 记录观测；写成功刷新
        }
    }

    private static String agentKey(String taskId) {
        return taskId == null ? "main" : taskId;
    }

    private static String extractPath(JsonNode toolInput) {
        if (toolInput == null || !toolInput.hasNonNull("path")) {
            return null;
        }
        return toolInput.get("path").asText();
    }

    private static Path resolve(String projectRoot, String path) {
        try {
            Path base = projectRoot == null || projectRoot.isBlank()
                    ? Path.of("").toAbsolutePath()
                    : Path.of(projectRoot);
            return base.resolve(path).normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 文件内容指纹（SHA-256）；文件不存在或读失败返回 null。 */
    private static String fingerprint(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (Exception e) {
            return null;
        }
    }
}
