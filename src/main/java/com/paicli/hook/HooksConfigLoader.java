package com.paicli.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 加载 {@code hooks.json} 并把每条命令 hook 构造成 {@link ShellHook} 租户注册项。
 *
 * 复用 {@code mcp.json} 的合并 / {@code ${VAR}} 展开机制（env → 系统属性 → 项目 {@code .env} → 用户 {@code .env}，
 * 外加 {@code ${PROJECT_DIR}} / {@code ${HOME}}）。schema 对齐 Claude Code：每事件一组
 * {@code {matcher, hooks:[{type:"command", command, timeout, statusMessage}]}}；matcher 是工具名正则（缺省=全部），
 * 只接受 {@code type:"command"}。
 *
 * 本 slice（issue 03）只激活**用户级**配置；项目级配置会被解析（因此非法内容照样报错）但**惰性**——
 * 不产出注册项，只留一条 trace。项目 Hook 信任（首次确认 + 指纹钉住）是 issue 04。
 */
public class HooksConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(HooksConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern VAR_PATTERN =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*|PROJECT_DIR|HOME)}");

    private static final Map<String, HookEvent> EVENTS = Map.of(
            "PreToolUse", HookEvent.PRE_TOOL_USE,
            "PostToolUse", HookEvent.POST_TOOL_USE,
            "Stop", HookEvent.STOP,
            "UserPromptSubmit", HookEvent.USER_PROMPT_SUBMIT);

    private final Path userConfig;
    private final Path projectConfig;
    private final Path projectDir;
    private final Consumer<String> statusSink;

    /** 一条 shell 租户注册项：事件 + 构造好的 {@link ShellHook}。 */
    public record Registration(HookEvent event, ShellHook hook) {}

    public HooksConfigLoader(Path projectDir, Consumer<String> statusSink) {
        this(Path.of(System.getProperty("user.home"), ".paicli", "hooks.json"),
                projectDir.resolve(".paicli").resolve("hooks.json"),
                projectDir, statusSink);
    }

    public HooksConfigLoader(Path userConfig, Path projectConfig, Path projectDir, Consumer<String> statusSink) {
        this.userConfig = userConfig;
        this.projectConfig = projectConfig;
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.statusSink = statusSink == null ? msg -> { } : statusSink;
    }

    /**
     * 加载用户级 hooks.json 并构造 shell 租户注册项；项目级只解析校验、不激活（inert）。
     * 非法条目（非 command 类型 / 缺 command / 未知事件的坏结构）抛 {@link IllegalArgumentException}。
     */
    public List<Registration> load() throws IOException {
        List<Registration> registrations = new ArrayList<>();
        if (Files.exists(userConfig)) {
            registrations.addAll(parse(userConfig));
        }
        if (Files.exists(projectConfig)) {
            List<Registration> projectHooks = parse(projectConfig);  // 解析校验，但惰性
            if (!projectHooks.isEmpty()) {
                log.info("项目级 hooks.json 已解析但未激活（{} 条）——项目 Hook 信任待 issue 04", projectHooks.size());
            }
        }
        return registrations;
    }

    private List<Registration> parse(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("hooks.json 顶层必须是对象: " + file);
        }
        List<Registration> registrations = new ArrayList<>();
        root.fields().forEachRemaining(entry -> {
            HookEvent event = EVENTS.get(entry.getKey());
            if (event == null) {
                log.warn("hooks.json 未知事件 '{}'，已跳过（{}）", entry.getKey(), file);
                return;
            }
            registrations.addAll(parseEventGroups(event, entry.getValue(), file));
        });
        return registrations;
    }

    private List<Registration> parseEventGroups(HookEvent event, JsonNode groups, Path file) {
        if (!groups.isArray()) {
            throw new IllegalArgumentException("hooks.json 事件 " + event + " 必须是数组: " + file);
        }
        List<Registration> registrations = new ArrayList<>();
        for (JsonNode group : groups) {
            Pattern matcher = parseMatcher(group.get("matcher"));
            JsonNode hooks = group.get("hooks");
            if (hooks == null || !hooks.isArray()) {
                throw new IllegalArgumentException("hooks.json 每组必须含 hooks 数组: " + file);
            }
            for (JsonNode hook : hooks) {
                registrations.add(new Registration(event, buildShellHook(hook, matcher, file)));
            }
        }
        return registrations;
    }

    private Pattern parseMatcher(JsonNode matcherNode) {
        if (matcherNode == null || matcherNode.isNull() || matcherNode.asText().isBlank()) {
            return null;  // 缺省 = 匹配该事件全部工具
        }
        try {
            return Pattern.compile(matcherNode.asText());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("hooks.json matcher 不是合法正则: " + matcherNode.asText());
        }
    }

    private ShellHook buildShellHook(JsonNode hook, Pattern matcher, Path file) {
        String type = hook.hasNonNull("type") ? hook.get("type").asText() : null;
        if (!"command".equals(type)) {
            throw new IllegalArgumentException(
                    "hooks.json 目前只支持 type=\"command\"（发现 '" + type + "'，缺少可执行 command）: " + file);
        }
        if (!hook.hasNonNull("command") || hook.get("command").asText().isBlank()) {
            throw new IllegalArgumentException("hooks.json command 类型 hook 必须含非空 command: " + file);
        }
        String command = expand(hook.get("command").asText());
        Duration timeout = hook.hasNonNull("timeout")
                ? Duration.ofSeconds(hook.get("timeout").asLong()) : null;
        String statusMessage = hook.hasNonNull("statusMessage") ? hook.get("statusMessage").asText() : null;
        String name = hook.hasNonNull("name") ? hook.get("name").asText() : "hooks.json:" + command;
        return new ShellHook(name, matcher, command, timeout, statusMessage, statusSink, MAPPER);
    }

    private String expand(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = VAR_PATTERN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = switch (name) {
                case "PROJECT_DIR" -> projectDir.toString();
                case "HOME" -> System.getProperty("user.home");
                default -> readConfiguredValue(name);
            };
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("hooks.json 引用了未设置的变量: " + name);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String readConfiguredValue(String key) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromProp = System.getProperty(key);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        String fromProjectEnv = readFromDotEnv(projectDir.resolve(".env"), key);
        if (fromProjectEnv != null) {
            return fromProjectEnv;
        }
        return readFromDotEnv(Path.of(System.getProperty("user.home"), ".env"), key);
    }

    private static String readFromDotEnv(Path file, String key) {
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith(key + "=")) {
                    String value = line.substring((key + "=").length()).trim();
                    return value.isBlank() ? null : value;
                }
            }
        } catch (IOException ignored) {
            // 读不到就当没配置
        }
        return null;
    }
}
