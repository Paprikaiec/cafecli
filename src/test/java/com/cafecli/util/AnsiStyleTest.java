package com.cafecli.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AnsiStyleTest {

    private static final java.util.regex.Pattern LEADING_STYLE =
            java.util.regex.Pattern.compile("^(?:\u001B\\[[;\\d]*m)+");

    @Test
    void userMessageBlockDoesNotForceWrapWhenContentExactlyFits() {
        String line = AnsiStyle.userMessageBlock("abc", 8);

        assertFalse(line.contains("\n"), line);
        assertTrue(stripAnsi(line).startsWith("> abc"), line);
        assertFalse(stripAnsi(line).startsWith(" "), line);
    }

    @Test
    void userMessageBlockKeepsExplicitMultilineInputAsRows() {
        String block = AnsiStyle.userMessageBlock("第一行\n第二行", 40);

        assertEquals(1, block.chars().filter(ch -> ch == '\n').count(), block);
        assertTrue(block.contains("第一行"), block);
        assertTrue(block.contains("第二行"), block);
    }

    @Test
    void coffeeCupBodyDropsGreenAndKeepsFourDistinctLayers() {
        assumeTrue(AnsiStyle.isEnabled());

        String cream = AnsiStyle.cupLayer("........", 0);
        String latte = AnsiStyle.cupLayer("|      |]", 1);
        String coffee = AnsiStyle.cupLayer("\\      /", 2);
        String base = AnsiStyle.cupLayer("`----'", 3);

        for (String layer : new String[]{cream, latte, coffee, base}) {
            assertFalse(layer.contains("\u001B[32m"), layer);
        }
        assertEquals(4, java.util.stream.Stream.of(cream, latte, coffee, base)
                .map(AnsiStyleTest::colorPrefix)
                .distinct()
                .count());
        assertNotEquals(colorPrefix(cream), colorPrefix(base));
    }

    @Test
    void cupLayerClampsOutOfRangeLayers() {
        assertEquals(AnsiStyle.cupLayer("x", 0), AnsiStyle.cupLayer("x", -3));
        assertEquals(AnsiStyle.cupLayer("x", 3), AnsiStyle.cupLayer("x", 99));
    }

    @Test
    void steamStaysDimGray() {
        assumeTrue(AnsiStyle.isEnabled());

        assertTrue(AnsiStyle.steam("( (").startsWith("\u001B[2m\u001B[90m"),
                AnsiStyle.steam("( ("));
    }

    /** 取行首连续的 ANSI 样式前缀，用来比较各层配色是否互不相同。 */
    private static String colorPrefix(String value) {
        java.util.regex.Matcher matcher = LEADING_STYLE.matcher(value);
        return matcher.find() ? matcher.group() : "";
    }

    private static String stripAnsi(String value) {
        return value.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
