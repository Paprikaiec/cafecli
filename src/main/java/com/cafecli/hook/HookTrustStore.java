package com.cafecli.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用户级项目 Hook 信任存档：把「哪个项目的 hooks.json 被用户批准过、批准时的内容指纹」钉在
 * {@code ~/.cafecli/hook-trust.json}（可注入路径供测试）。按项目键隔离——批准一个项目不信任另一个；
 * 内容变更（指纹变）即失去钉住、下次重新确认。
 *
 * 格式：{@code { "<项目绝对路径>": "<sha256 指纹>" }}。
 */
public class HookTrustStore {

    private static final Logger log = LoggerFactory.getLogger(HookTrustStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path trustFile;

    public HookTrustStore() {
        this(com.cafecli.config.ConfigHome.userConfigDir().resolve("hook-trust.json"));
    }

    public HookTrustStore(Path trustFile) {
        this.trustFile = trustFile;
    }

    /** 该项目当前内容指纹是否已被用户钉住（键存在且指纹相等）。 */
    public boolean isPinned(String projectKey, String fingerprint) {
        if (projectKey == null || fingerprint == null) {
            return false;
        }
        ObjectNode root = read();
        return root.hasNonNull(projectKey) && fingerprint.equals(root.get(projectKey).asText());
    }

    /** 钉住该项目的内容指纹（覆盖旧值），落盘 best-effort。 */
    public synchronized void pin(String projectKey, String fingerprint) {
        if (projectKey == null || fingerprint == null) {
            return;
        }
        ObjectNode root = read();
        root.put(projectKey, fingerprint);
        try {
            Files.createDirectories(trustFile.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(trustFile.toFile(), root);
        } catch (IOException e) {
            log.warn("写入项目 Hook 信任存档失败: {}", e.toString());
        }
    }

    private ObjectNode read() {
        if (!Files.exists(trustFile)) {
            return MAPPER.createObjectNode();
        }
        try {
            var node = MAPPER.readTree(trustFile.toFile());
            return node instanceof ObjectNode obj ? obj : MAPPER.createObjectNode();
        } catch (IOException e) {
            log.warn("读取项目 Hook 信任存档失败，视为空: {}", e.toString());
            return MAPPER.createObjectNode();
        }
    }
}
