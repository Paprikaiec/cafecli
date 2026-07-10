package com.paicli.hook;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 收尾门（Finish Gate）：{@code Stop} 点的内建租户。
 *
 * 触发条件（同时满足才 BLOCK）：本回合有过**成功的写类工具**，且**最后一次成功写入之后没有任何验证命令成功执行**。
 * 命中则阻断收尾，把「先自证再交付」回灌模型续跑；有限次阻断（{@code blockCap}）防死循环——
 * 硬轮数上限始终兜底（阻断消耗轮数）。
 *
 * 验证检测是**可配置的命令正则启发式**，只匹配 agent 自己在工作区跑的 {@code execute_command}
 * （默认覆盖 mvn/mvnw/gradle/gradlew/npm test/pytest/go test 等）——**绝不引用任何外部评分测试集，
 * 绝不按评测实例参数化**（用户硬红线）。
 */
public class FinishGateTenant implements HookTenant {

    /** 默认验证命令正则：常见 JVM / JS / Python / Go / Rust / Make 的测试或构建入口。 */
    public static final String DEFAULT_VERIFY_REGEX =
            "(?i)(\\bmvn\\b|\\./mvnw|\\bmvnw\\b|\\bgradle\\b|\\./gradlew|\\bgradlew\\b"
                    + "|npm\\s+(run\\s+)?test|yarn\\s+test|pnpm\\s+(run\\s+)?test"
                    + "|\\bpytest\\b|py\\.test|python\\s+-m\\s+pytest|\\bgo\\s+test\\b|\\bcargo\\s+test\\b"
                    + "|\\bctest\\b|make\\s+(test|check))";

    static final int DEFAULT_BLOCK_CAP = 2;

    static final String BLOCK_REASON =
            "你本回合修改了文件，但最后一次写入之后没有运行任何验证命令。"
                    + "请在工作区内运行相关测试或构建命令（如 mvn test / gradle test / npm test / pytest / go test）"
                    + "自证改动可用后再结束；不要凭假设交付。";

    /** 写类工具集合——成功执行即视为“本回合有写入”。 */
    static final java.util.Set<String> WRITE_TOOLS =
            java.util.Set.of("write_file", "edit_file", "create_project");

    /** execute_command 的名字（验证命令只在它里面找）。 */
    static final String EXECUTE_COMMAND = "execute_command";

    private final Pattern verifyPattern;
    private final int blockCap;

    public FinishGateTenant(Pattern verifyPattern, int blockCap) {
        this.verifyPattern = verifyPattern;
        this.blockCap = blockCap;
    }

    /** 从系统属性构造：验证正则与阻断上限可配置（见 {@link HookProperties}）。 */
    public static FinishGateTenant fromSystemProperties() {
        String regex = HookProperties.string(HookProperties.FINISH_GATE_VERIFY_REGEX, DEFAULT_VERIFY_REGEX);
        int cap = HookProperties.intValue(HookProperties.FINISH_GATE_BLOCK_CAP, DEFAULT_BLOCK_CAP);
        return new FinishGateTenant(Pattern.compile(regex), cap);
    }

    /** 收尾门是否启用（注册期由 Agent 调用；沿 safe-edit 先例，默认关）。 */
    public static boolean enabledBySystemProperty() {
        return HookProperties.flag(HookProperties.FINISH_GATE_ENABLED);
    }

    @Override
    public String name() {
        return "finish-gate";
    }

    @Override
    public HookDecision evaluate(HookPayload payload) {
        if (payload == null || payload.event() != HookEvent.STOP) {
            return HookDecision.cont();
        }
        // 反循环：已达阻断上限就放行收尾，硬轮数上限仍是最终兜底。
        if (payload.blockCount() >= blockCap) {
            return HookDecision.cont();
        }
        List<HookPayload.TurnTool> tools = payload.turnToolSummary();
        if (tools == null || tools.isEmpty()) {
            return HookDecision.cont();  // 只读 / 纯问答回合：静默
        }
        int lastWriteIndex = lastSuccessfulWriteIndex(tools);
        if (lastWriteIndex < 0) {
            return HookDecision.cont();  // 本回合无成功写入：静默
        }
        if (verifiedAfter(tools, lastWriteIndex)) {
            return HookDecision.cont();  // 写后已有验证命令成功执行：静默
        }
        return HookDecision.block(BLOCK_REASON);
    }

    private static int lastSuccessfulWriteIndex(List<HookPayload.TurnTool> tools) {
        int last = -1;
        for (int i = 0; i < tools.size(); i++) {
            HookPayload.TurnTool tool = tools.get(i);
            if (tool.success() && WRITE_TOOLS.contains(tool.name())) {
                last = i;
            }
        }
        return last;
    }

    private boolean verifiedAfter(List<HookPayload.TurnTool> tools, int lastWriteIndex) {
        for (int i = lastWriteIndex + 1; i < tools.size(); i++) {
            HookPayload.TurnTool tool = tools.get(i);
            if (tool.success()
                    && EXECUTE_COMMAND.equals(tool.name())
                    && tool.target() != null
                    && verifyPattern.matcher(tool.target()).find()) {
                return true;
            }
        }
        return false;
    }
}
