package com.cafecli.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 品牌配置目录解析：优先使用新目录 {@code ~/.cafecli} / {@code .cafecli}，
 * 但当只存在旧目录 {@code ~/.paicli} / {@code .paicli} 时回退到旧目录，
 * 让 paicli 时代的既有安装（记忆 / 历史 / mcp.json / 快照 / 审计）继续可用。
 *
 * <p>规则：目标目录已存在，或旧目录不存在时 → 用新目录；否则（仅旧目录存在）→ 用旧目录。
 * 新用户落在 {@code ~/.cafecli}，老用户无需迁移即可继续读写 {@code ~/.paicli}。
 */
public final class ConfigHome {

    private static final String NEW_DIR = ".cafecli";
    private static final String LEGACY_DIR = ".paicli";

    private ConfigHome() {}

    /** 用户级配置目录（{@code ~/.cafecli}，回退 {@code ~/.paicli}）。 */
    public static Path userConfigDir() {
        return configDirUnder(Path.of(System.getProperty("user.home")));
    }

    /** 项目级配置目录（当前工作目录下的 {@code .cafecli}，回退 {@code .paicli}）。 */
    public static Path projectConfigDir() {
        return configDirUnder(Path.of(""));
    }

    /** 指定根目录下的配置目录（{@code <base>/.cafecli}，回退 {@code <base>/.paicli}）。 */
    public static Path configDirUnder(Path base) {
        return preferred(base.resolve(NEW_DIR), base.resolve(LEGACY_DIR));
    }

    /** 新配置目录名（忽略列表 / 目录名比较用）。 */
    public static String dirName() {
        return NEW_DIR;
    }

    /** 旧配置目录名（向后兼容的忽略列表 / 目录名比较用）。 */
    public static String legacyDirName() {
        return LEGACY_DIR;
    }

    /** 读环境变量：优先新名 {@code CAFECLI_*}，回退旧名 {@code PAICLI_*}。 */
    public static String env(String preferredName, String legacyName) {
        String value = System.getenv(preferredName);
        return value != null ? value : System.getenv(legacyName);
    }

    /** 给旧环境变量名（{@code PAICLI_*}），自动优先其 {@code CAFECLI_*} 新名，回退旧名。 */
    public static String envLegacy(String legacyName) {
        String preferred = legacyName.startsWith("PAICLI_")
                ? "CAFECLI_" + legacyName.substring("PAICLI_".length())
                : legacyName;
        return env(preferred, legacyName);
    }

    /**
     * 把用户提供的 {@code cafecli.*} 系统属性桥接到历史 {@code paicli.*} 键：
     * 内部读点仍读 {@code paicli.*}，用户既可用新 {@code -Dcafecli.*} 也可用旧
     * {@code -Dpaicli.*}（两者同时给出时新键优先）。应在 {@code main()} 最早处调用一次。
     */
    public static void installPropertyAliases() {
        java.util.Properties props = System.getProperties();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("cafecli.")) {
                String legacy = "paicli." + name.substring("cafecli.".length());
                String value = props.getProperty(name);
                if (value != null) {
                    System.setProperty(legacy, value);
                }
            }
        }
    }

    private static Path preferred(Path preferred, Path legacy) {
        if (Files.exists(preferred) || !Files.exists(legacy)) {
            return preferred;
        }
        return legacy;
    }
}
