package com.cafecli.harness.config;

import java.nio.file.Path;

/** Explicit skill directories, including the built-in extraction cache and persisted enable state. */
public record SkillsConfig(Path builtinCache, Path userSkills, Path projectSkills, Path stateFile) {
    public static SkillsConfig under(ConfigHomes homes) {
        return new SkillsConfig(homes.user().resolve("skills-cache"), homes.user().resolve("skills"),
                homes.project().resolve("skills"), homes.user().resolve("skills.json"));
    }
}
