package com.cafecli.cli;

import com.cafecli.skill.SkillCommands;
import com.cafecli.skill.SkillRegistry;
import com.cafecli.skill.SkillStateStore;

/** v1 CLI adapter for the shared skill command leaf. */
final class SkillCommandHandler {
    private SkillCommandHandler() { }
    static String startupSummary(SkillRegistry registry) { return SkillCommands.startupSummary(registry); }
    static String list(SkillRegistry registry) { return SkillCommands.list(registry); }
    static String show(SkillRegistry registry, String name) { return SkillCommands.show(registry, name); }
    static String enable(SkillRegistry registry, SkillStateStore state, String name) {
        return SkillCommands.enable(registry, state, name);
    }
    static String disable(SkillRegistry registry, SkillStateStore state, String name) {
        return SkillCommands.disable(registry, state, name);
    }
}
