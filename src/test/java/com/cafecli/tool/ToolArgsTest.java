package com.cafecli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolArgs} 必须与老路径 {@code fromMap}（{@code asText()} 拍平）+ {@code parseInt} / {@code clamp} /
 * {@code parseBoolean} 逐字节一致——工具从 fromMap 迁到 ToolArgs 不得改变任何参数解读。
 */
class ToolArgsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ToolArgs args(String json) throws Exception {
        return ToolArgs.of(MAPPER.readTree(json));
    }

    /**
     * 老路径：已删除的 {@code ToolRegistry.fromMap} 实际交给工具的那张 Map。
     * 这是那段拍平循环的<b>冻结副本</b>（逐字搬自删除前的实现，不要跟着 ToolArgs 改）：它就是旧行为本身，
     * ToolArgs 必须一直与它对得上。
     */
    private static Map<String, String> legacyMap(String json) throws Exception {
        JsonNode args = MAPPER.readTree(json);
        Map<String, String> argMap = new HashMap<>();
        if (args != null) {
            args.fields().forEachRemaining(entry ->
                argMap.put(entry.getKey(), entry.getValue().asText()));
        }
        return argMap;
    }

    @Test
    void strMatchesFromMapForEveryJsonValueShape() throws Exception {
        List<String> values = List.of(
                "\"text\"", "\"\"", "\"  7 \"", "7", "-3", "3.5", "true", "false", "null", "{\"a\":1}", "[1,2]");
        for (String value : values) {
            String json = "{\"k\":" + value + "}";
            assertEquals(legacyMap(json).get("k"), args(json).str("k"), "value=" + value);
        }
        assertEquals(legacyMap("{}").get("k"), args("{}").str("k"));
    }

    @Test
    void jsonNullReadsAsTheStringNullAndContainersReadAsEmpty() throws Exception {
        assertEquals("null", args("{\"k\":null}").str("k"));
        assertEquals("", args("{\"k\":{\"a\":1}}").str("k"));
        assertEquals("", args("{\"k\":[1]}").str("k"));
        assertEquals("7", args("{\"k\":7}").str("k"));
    }

    @Test
    void hasIsTrueForPresentNullAndFalseForMissing() throws Exception {
        assertTrue(args("{\"k\":null}").has("k"));
        assertTrue(args("{\"k\":\"\"}").has("k"));
        assertFalse(args("{}").has("k"));
    }

    @Test
    void strWithDefaultOnlyAppliesWhenFieldIsMissing() throws Exception {
        assertEquals(".", args("{}").str("path", "."));
        assertEquals("null", args("{\"path\":null}").str("path", "."));
        assertEquals("", args("{\"path\":\"\"}").str("path", "."));
        assertEquals("src", args("{\"path\":\"src\"}").str("path", "."));
    }

    @Test
    void nullOrNonObjectNodeBehavesLikeAnEmptyArgumentMap() throws Exception {
        ToolArgs fromNull = ToolArgs.of(null);
        assertFalse(fromNull.has("k"));
        assertNull(fromNull.str("k"));
        assertEquals("d", fromNull.str("k", "d"));
        assertEquals(5, fromNull.intOr("k", 5));
        assertTrue(fromNull.bool("k", true));

        JsonNode array = MAPPER.readTree("[1,2]");
        assertFalse(ToolArgs.of(array).has("0"));
        assertNull(ToolArgs.of(array).str("k"));
        assertTrue(legacyMap("[1,2]").isEmpty(), "fromMap 对非对象节点也是空表");
    }

    @Test
    void intOrTrimsAndFallsBackOnNullBlankOrGarbage() throws Exception {
        assertEquals(7, args("{\"n\":7}").intOr("n", 5));
        assertEquals(7, args("{\"n\":\"7\"}").intOr("n", 5));
        assertEquals(7, args("{\"n\":\" 7 \"}").intOr("n", 5));
        assertEquals(-3, args("{\"n\":-3}").intOr("n", 5));
        assertEquals(5, args("{}").intOr("n", 5));
        assertEquals(5, args("{\"n\":\"\"}").intOr("n", 5));
        assertEquals(5, args("{\"n\":\"   \"}").intOr("n", 5));
        assertEquals(5, args("{\"n\":null}").intOr("n", 5));
        assertEquals(5, args("{\"n\":\"abc\"}").intOr("n", 5));
        assertEquals(5, args("{\"n\":3.5}").intOr("n", 5));
        assertEquals(5, args("{\"n\":99999999999}").intOr("n", 5));
        assertEquals(5, args("{\"n\":{\"a\":1}}").intOr("n", 5));
    }

    @Test
    void intInClampsBothTheParsedValueAndTheFallback() throws Exception {
        assertEquals(1, args("{\"n\":0}").intIn("n", 50, 1, 200));
        assertEquals(200, args("{\"n\":9999}").intIn("n", 50, 1, 200));
        assertEquals(42, args("{\"n\":42}").intIn("n", 50, 1, 200));
        assertEquals(50, args("{\"n\":\"x\"}").intIn("n", 50, 1, 200));
        assertEquals(30, args("{}").intIn("n", 99, 1, 30));
        assertEquals(ToolArgs.clamp(ToolArgs.parseInt("-8", 0), 0, 5), args("{\"n\":-8}").intIn("n", 0, 0, 5));
    }

    @Test
    void boolAcceptsTrueOneYesCaseInsensitivelyAfterTrim() throws Exception {
        for (String truthy : List.of("true", "\"TRUE\"", "\" Yes \"", "\"1\"", "1", "\"yEs\"")) {
            assertTrue(args("{\"b\":" + truthy + "}").bool("b", false), truthy);
        }
        for (String falsy : List.of("false", "\"0\"", "0", "\"no\"", "\"2\"", "null", "\"on\"")) {
            assertFalse(args("{\"b\":" + falsy + "}").bool("b", true), falsy);
        }
    }

    @Test
    void boolFallsBackOnMissingBlankOrContainer() throws Exception {
        assertTrue(args("{}").bool("b", true));
        assertFalse(args("{}").bool("b", false));
        assertTrue(args("{\"b\":\"\"}").bool("b", true));
        assertTrue(args("{\"b\":\"  \"}").bool("b", true));
        assertTrue(args("{\"b\":[]}").bool("b", true));
    }
}
