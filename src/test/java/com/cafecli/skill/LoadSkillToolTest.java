package com.cafecli.skill;

import com.cafecli.tool.SkillTools;
import com.cafecli.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {

    @Test
    void loadsExistingSkillIntoBuffer(@TempDir Path tempDir) throws Exception {
        SkillRegistry registry = registryWith(tempDir, "web-access", "决策手册",
                "# Body\nwhen to fetch\nwhen to browse\n");
        SkillContextBuffer buffer = new SkillContextBuffer();
        Tool tools = SkillTools.loadSkill(registry, buffer);

        String result = tools.executor().execute(new ObjectMapper().readTree("{\"name\":\"web-access\"}")).renderTextForLlm();

        assertTrue(result.contains("已加载 skill 'web-access'"), result);
        assertFalse(buffer.isEmpty());
        String drained = buffer.drain();
        assertTrue(drained.contains("when to fetch"));
        assertTrue(drained.contains("已加载 Skill：web-access"));
    }

    @Test
    void failsForUnknownSkill(@TempDir Path tempDir) throws Exception {
        SkillRegistry registry = registryWith(tempDir, "real-one", "desc", "body");
        SkillContextBuffer buffer = new SkillContextBuffer();
        Tool tools = SkillTools.loadSkill(registry, buffer);

        String result = tools.executor().execute(new ObjectMapper().readTree("{\"name\":\"nonexistent\"}")).renderTextForLlm();
        assertTrue(result.contains("未找到"), result);
        assertTrue(buffer.isEmpty());
    }

    @Test
    void failsForDisabledSkill(@TempDir Path tempDir) throws Exception {
        Path stateFile = tempDir.resolve("skills.json");
        SkillStateStore state = new SkillStateStore(stateFile);
        state.disable("web-access");

        SkillRegistry registry = new SkillRegistry(null,
                writeUserSkill(tempDir, "web-access", "desc", "body").getParent().getParent(),
                null, state);
        registry.reload();

        SkillContextBuffer buffer = new SkillContextBuffer();
        Tool tools = SkillTools.loadSkill(registry, buffer);

        String result = tools.executor().execute(new ObjectMapper().readTree("{\"name\":\"web-access\"}")).renderTextForLlm();
        assertTrue(result.contains("已被禁用"), result);
        assertTrue(buffer.isEmpty());
    }

    @Test
    void truncatesOversizedBody(@TempDir Path tempDir) throws Exception {
        StringBuilder big = new StringBuilder();
        while (big.length() < 6 * 1024) big.append("0123456789");
        SkillRegistry registry = registryWith(tempDir, "huge", "desc", big.toString());

        SkillContextBuffer buffer = new SkillContextBuffer();
        Tool tools = SkillTools.loadSkill(registry, buffer);

        String result = tools.executor().execute(new ObjectMapper().readTree("{\"name\":\"huge\"}")).renderTextForLlm();
        assertTrue(result.contains("已加载 skill 'huge'"));

        String drained = buffer.drain();
        assertTrue(drained.contains("(skill body truncated"), "应包含截断标记");
    }

    @Test
    void failsWhenNameMissing() throws Exception {
        Tool tools = SkillTools.loadSkill(new SkillRegistry(null, null, null, null), new SkillContextBuffer());

        String result = tools.executor().execute(new ObjectMapper().readTree("{}")).renderTextForLlm();
        assertTrue(result.contains("name 不能为空"), result);
    }

    private static SkillRegistry registryWith(Path tempDir, String name, String desc, String body) throws Exception {
        Path userRoot = writeUserSkill(tempDir, name, desc, body).getParent().getParent();
        SkillStateStore state = new SkillStateStore(tempDir.resolve("skills.json"));
        SkillRegistry registry = new SkillRegistry(null, userRoot, null, state);
        registry.reload();
        return registry;
    }

    private static Path writeUserSkill(Path tempDir, String name, String desc, String body) throws Exception {
        Path userRoot = tempDir.resolve("user-skills");
        Path skillDir = userRoot.resolve(name);
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd,
                "---\nname: " + name
                        + "\ndescription: " + desc
                        + "\n---\n" + body + "\n");
        return skillMd;
    }
}
