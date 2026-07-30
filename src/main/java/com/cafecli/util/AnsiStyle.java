package com.cafecli.util;

/**
 * 终端 ANSI 样式辅助。
 */
public final class AnsiStyle {
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String ITALIC = "\u001B[3m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String GRAY = "\u001B[90m";
    private static final String WHITE = "\u001B[37m";
    private static final String PURPLE = "\u001B[38;5;141m";
    private static final String BG_PANEL = "\u001B[48;5;236m";
    private static final boolean ENABLED = determineEnabled();
    private static final boolean COLORS_256 = determine256Colors();

    /** 咖啡杯 logo 的杯体配色：index 0 是最上层的奶油白，越往下越接近杯底的深咖啡棕。 */
    private static final String[] CUP_LAYERS_256 = {
            "\u001B[38;5;223m",  // 奶油白：咖啡表面 / 杯口
            "\u001B[38;5;180m",  // 拿铁棕
            "\u001B[38;5;137m",  // 咖啡棕
            "\u001B[38;5;94m"    // 深烘焙棕
    };

    /** 只有 16 色的终端没有棕色，退化成 亮白 -> 亮黄 -> 黄 -> 暗黄 的同向明暗渐变。 */
    private static final String[] CUP_LAYERS_16 = {
            BOLD + WHITE,
            BOLD + YELLOW,
            YELLOW,
            DIM + YELLOW
    };

    private AnsiStyle() {
    }

    public static String heading(String text) {
        return wrap(BOLD + CYAN, text);
    }

    public static String section(String text) {
        return wrap(BOLD + GREEN, text);
    }

    public static String subtle(String text) {
        return wrap(DIM + GRAY, text);
    }

    /** 咖啡杯 logo 的蒸汽：暗灰，只作陪衬不抢眼。 */
    public static String steam(String text) {
        return wrap(DIM + GRAY, text);
    }

    /**
     * 咖啡杯 logo 的杯体渐变。
     *
     * @param layer 0 = 最上层的奶油白（咖啡表面），层号越大越接近杯底的深咖啡棕；越界会被截断到两端
     */
    public static String cupLayer(String text, int layer) {
        String[] palette = COLORS_256 ? CUP_LAYERS_256 : CUP_LAYERS_16;
        int index = Math.max(0, Math.min(palette.length - 1, layer));
        return wrap(palette[index], text);
    }

    public static String thinking(String text) {
        return wrap(ITALIC + GRAY, text);
    }

    public static String userMessageBlock(String text, int columns) {
        String safe = text == null ? "" : text.strip();
        int width = Math.max(20, columns);
        String[] lines = safe.isEmpty() ? new String[]{""} : safe.split("\\R", -1);
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                rendered.append('\n');
            }
            rendered.append(userMessageBlockLine(lines[i], width));
        }
        return rendered.toString();
    }

    private static String userMessageBlockLine(String text, int width) {
        String safe = text == null ? "" : text;
        String prefix = "> ";
        String content = prefix + safe;
        int padding = Math.max(0, width - displayWidth(content));
        String line = content + " ".repeat(padding);
        if (!ENABLED) {
            return line.stripTrailing();
        }
        return BG_PANEL + PURPLE + prefix + RESET + BG_PANEL + safe + " ".repeat(padding) + RESET;
    }

    public static String codeLabel(String text) {
        return wrap(BOLD + YELLOW, text);
    }

    public static String error(String text) {
        return wrap(BOLD + RED, text);
    }

    public static String quotePrefix(String text) {
        return wrap(DIM + CYAN, text);
    }

    public static String emphasis(String text) {
        return wrap(BOLD, text);
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    private static String wrap(String prefix, String text) {
        if (!ENABLED || text == null || text.isEmpty()) {
            return text;
        }
        return prefix + text + RESET;
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
            width += block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                    || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                    || block == Character.UnicodeBlock.HIRAGANA
                    || block == Character.UnicodeBlock.KATAKANA
                    || block == Character.UnicodeBlock.EMOTICONS
                    || block == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_PICTOGRAPHS
                    || block == Character.UnicodeBlock.TRANSPORT_AND_MAP_SYMBOLS ? 2 : 1;
            i += Character.charCount(codePoint);
        }
        return width;
    }

    /** 256 色调色板是否可用——棕色系只有 256 色以上才有，16 色终端要走明暗渐变兜底。 */
    private static boolean determine256Colors() {
        String colorterm = System.getenv("COLORTERM");
        if (colorterm != null
                && ("truecolor".equalsIgnoreCase(colorterm) || "24bit".equalsIgnoreCase(colorterm))) {
            return true;
        }
        String term = System.getenv("TERM");
        if (term == null) {
            return false;
        }
        String normalized = term.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("256") || normalized.contains("direct")
                || normalized.contains("truecolor");
    }

    private static boolean determineEnabled() {
        String property = System.getProperty("paicli.render.color");
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }

        if (System.getenv("NO_COLOR") != null) {
            return false;
        }

        String term = System.getenv("TERM");
        return term == null || !term.equalsIgnoreCase("dumb");
    }
}
