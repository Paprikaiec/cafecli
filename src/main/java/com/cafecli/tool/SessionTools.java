package com.cafecli.tool;

import com.cafecli.skill.Skill;
import com.cafecli.skill.SkillContextBuffer;
import com.cafecli.skill.SkillRegistry;
import com.cafecli.snapshot.RestoreResult;
import com.cafecli.snapshot.SnapshotService;
import com.cafecli.tool.ToolOutput.Status;
import com.cafecli.tool.ToolRegistry.Param;
import com.cafecli.tool.ToolRegistry.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.cafecli.tool.ToolRegistry.createParameters;

/**
 * 会话相邻的三个工具：{@code save_memory}（长期记忆写入）/ {@code load_skill}（Skill 正文注入下一轮）/
 * {@code revert_turn}（恢复 pre-turn 快照）。
 *
 * <p>四个协作者都在注册之后才会被换掉：记忆写入器 / Skill 索引 / Skill 缓冲各有 setter（子任务 registry 也在注册后才接过父的、
 * 并换上自己全新的 Skill 缓冲），快照服务会被 {@code setSnapshotService} / {@code setProjectPath} 与子任务构造替换。
 * 所以一律经 supplier 在<b>每次调用时</b>现取（规则见 {@link ToolEnv}）。
 */
final class SessionTools {
    private final Supplier<BiConsumer<String, String>> memorySaver;
    private final Supplier<SkillRegistry> skillRegistry;
    private final Supplier<SkillContextBuffer> skillContextBuffer;
    private final Supplier<SnapshotService> snapshotService;

    SessionTools(Supplier<BiConsumer<String, String>> memorySaver, Supplier<SkillRegistry> skillRegistry,
                 Supplier<SkillContextBuffer> skillContextBuffer, Supplier<SnapshotService> snapshotService) {
        this.memorySaver = memorySaver;
        this.skillRegistry = skillRegistry;
        this.skillContextBuffer = skillContextBuffer;
        this.snapshotService = snapshotService;
    }

    List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool(
                "save_memory",
                "当且仅当用户明确说“记一下”“记住”“以后记得”或要求保存长期偏好/稳定事实时调用，把精炼事实写入长期记忆；scope 默认 project，跨项目偏好才用 global；不要保存一次性任务请求、临时文件名或模型猜测。",
                createParameters(
                        new Param("fact", "string", "要长期保存的稳定事实或用户偏好，必须精炼、可跨会话复用", true),
                        new Param("scope", "string", "记忆作用域：project 或 global。默认 project；跨项目长期偏好才用 global", false)
                ),
                args -> {
                    ToolArgs a = ToolArgs.of(args);
                    String fact = a.str("fact");
                    if (fact == null || fact.isBlank()) {
                        return ToolOutput.failure(Status.ERROR, "保存长期记忆失败: fact 不能为空");
                    }
                    BiConsumer<String, String> saver = memorySaver.get();
                    if (saver == null) {
                        return ToolOutput.failure(Status.ERROR, "保存长期记忆失败: 记忆保存器未初始化");
                    }
                    String normalized = fact.trim();
                    String scope = "global".equalsIgnoreCase(a.str("scope")) ? "global" : "project";
                    saver.accept(normalized, scope);
                    return ToolOutput.text("💾 已保存到长期记忆(" + scope + "): " + normalized);
                }
        ));

        tools.add(new Tool(
                "load_skill",
                "Load full SKILL.md instructions for a skill the system has indexed (see the \"可用 Skills\" section in this system prompt). Call this when a skill's description matches the current task. Pass the exact kebab-case skill name. The full body will appear at the start of your next user message under \"## 已加载 Skill：<name>\". Don't reload the same skill twice in one session.",
                createParameters(new Param("name", "string", "the exact kebab-case skill name (e.g. web-access)", true)),
                args -> {
                    ToolArgs a = ToolArgs.of(args);
                    String name = a.str("name");
                    if (name == null || name.isBlank()) {
                        return ToolOutput.failure(Status.ERROR, "load_skill 失败: name 不能为空");
                    }
                    SkillRegistry skills = skillRegistry.get();
                    if (skills == null) {
                        return ToolOutput.failure(Status.ERROR, "load_skill 失败: Skill 系统未初始化");
                    }
                    Skill skill = skills.findSkill(name);
                    if (skill == null) {
                        Skill any = skills.findAnySkill(name);
                        if (any == null) {
                            return ToolOutput.failure(Status.ERROR, "Skill '" + name + "' 未找到，可用 /skill list 查看可用 skill");
                        }
                        return ToolOutput.failure(Status.ERROR, "Skill '" + name + "' 已被禁用，可用 /skill on " + name + " 启用");
                    }
                    String body = skill.body();
                    int originalLen = body == null ? 0 : body.length();
                    int max = 5 * 1024;
                    String injected = body == null ? "" : body;
                    if (injected.length() > max) {
                        injected = injected.substring(0, max)
                                + "\n\n...(skill body truncated, full content via /skill show " + name + ")";
                    }
                    SkillContextBuffer buffer = skillContextBuffer.get();
                    if (buffer != null) {
                        buffer.push(name, injected);
                    }
                    return ToolOutput.text("已加载 skill '" + name + "' 的完整指引（" + originalLen
                            + " bytes），将在下一轮上下文中以 \"## 已加载 Skill：" + name + "\" 段出现。");
                }
        ));

        tools.add(new Tool(
                "revert_turn",
                "恢复到 Side-Git 记录的最近第 N 个 pre-turn 快照。会先记录 pre-restore 快照；属于高危写入操作，必须经 HITL 审批。",
                createParameters(new Param("offset", "integer", "要恢复的 pre-turn 快照序号，1 表示最近一次任务开始前", false)),
                args -> {
                    ToolArgs a = ToolArgs.of(args);
                    int offset = a.intOr("offset", 1);
                    try {
                        RestoreResult result = snapshotService.get().restorePreTurn(Math.max(1, offset));
                        // 恢复没成功时文本仍是 formatForCli() 的那份，只是状态换成 ERROR
                        return result.success()
                                ? ToolOutput.text(result.formatForCli())
                                : ToolOutput.failure(Status.ERROR, result.formatForCli());
                    } catch (Exception e) {
                        return ToolOutput.failure(Status.ERROR, "恢复快照失败: " + e.getMessage());
                    }
                }
        ));
        return tools;
    }
}
