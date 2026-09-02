package com.cafecli.render.inline;

/**
 * Reads a stream of terminal bytes the way a user reads a screen.
 *
 * <p>It exists for one assertion that cannot be phrased any other way: "during [[geometry
 * turbulence]] only the erase reaches the terminal — no content bytes of any kind" (ADR 0010
 * amendment A3b) is inherently a claim about what was written. Phrasing it as the <b>absence of
 * content</b> rather than as equality with an expected byte string keeps it from becoming the kind of
 * emitted-sequence assertion that let four earlier rounds pass while the real terminal stayed broken.
 */
final class TerminalBytes {

    private static final char ESC = 27;
    private static final char BEL = 7;

    private TerminalBytes() {
    }

    /**
     * The characters the terminal would actually paint: escape sequences removed, then whitespace and
     * control bytes dropped. An empty result means nothing was displayed — cursor moves, erases and
     * mode switches all vanish, because none of them is content.
     */
    static String displayedCharacters(String bytes) {
        String text = stripEscapeSequences(bytes);
        StringBuilder painted = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch > ' ' && ch != 127) {
                painted.append(ch);
            }
        }
        return painted.toString();
    }

    static int occurrencesOf(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String stripEscapeSequences(String bytes) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < bytes.length()) {
            if (bytes.charAt(i) != ESC) {
                out.append(bytes.charAt(i++));
                continue;
            }
            i++;
            if (i >= bytes.length()) {
                break;
            }
            char introducer = bytes.charAt(i++);
            if (introducer == '[') {
                // CSI: parameter and intermediate bytes (0x20-0x3f) then one final byte (0x40-0x7e)
                while (i < bytes.length() && bytes.charAt(i) >= ' ' && bytes.charAt(i) <= '?') {
                    i++;
                }
                if (i < bytes.length()) {
                    i++;
                }
            } else if (introducer == ']') {
                // OSC: anything up to BEL or ST
                while (i < bytes.length() && bytes.charAt(i) != BEL && bytes.charAt(i) != ESC) {
                    i++;
                }
                if (i < bytes.length() && bytes.charAt(i) == BEL) {
                    i++;
                } else if (i + 1 < bytes.length()) {
                    i += 2;
                }
            }
            // anything else is a two-character escape, already consumed
        }
        return out.toString();
    }
}
