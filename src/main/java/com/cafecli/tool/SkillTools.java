package com.cafecli.tool;

import com.cafecli.skill.SkillContextBuffer;
import com.cafecli.skill.SkillRegistry;

/** The existing load_skill leaf without constructing the v1 registry. */
public final class SkillTools {
    private SkillTools() { }
    public static Tool loadSkill(SkillRegistry registry, SkillContextBuffer buffer) {
        return new SessionTools(() -> null, () -> registry, () -> buffer, () -> null).tools().stream()
                .filter(tool -> "load_skill".equals(tool.name())).findFirst().orElseThrow();
    }
}
