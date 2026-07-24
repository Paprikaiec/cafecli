package com.paicli.render.inline;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.Display;
import org.jline.utils.NonBlockingReader;

import java.util.ArrayList;
import java.util.List;

/** Thin main-thread key-reading shell around the pure transcript overlay model. */
final class TranscriptOverlay {

    private static final String ENTER_ALTERNATE_SCREEN = "\u001b[?1049h";
    private static final String LEAVE_ALTERNATE_SCREEN = "\u001b[?1049l";
    private static final String ENABLE_MOUSE_TRACKING = "\u001b[?1000h\u001b[?1006h";
    private static final String DISABLE_MOUSE_TRACKING = "\u001b[?1006l\u001b[?1000l";

    // DECSTBM reset (ESC [ r): drop any bottom scroll region the BottomStatusBar dock left active so
    // the fullscreen Display owns the whole screen (belt-and-suspenders alongside statusBar.suspend()).
    private static final String RESET_SCROLL_REGION = ((char) 27) + "[r";

    private final Terminal terminal;

    TranscriptOverlay(Terminal terminal) {
        this.terminal = terminal;
    }

    void open(List<RetainedWindow.TurnView> turns) {
        Document document = flatten(turns);
        int pageSize = Math.max(1, terminal.getHeight() - 1);
        List<Boolean> foldable = document.blocks().stream().map(StripEntry::isFoldable).toList();
        List<Boolean> thinking = document.blocks().stream().map(StripEntry::isThinking).toList();
        List<FoldState> retainedStates = document.blocks().stream()
                .map(StripEntry::foldState).toList();
        TranscriptOverlayModel.State state = TranscriptOverlayModel.start(
                foldable, thinking, retainedStates, pageSize);
        Display display = new Display(terminal, true);
        // Defer writing the bottom-right cell so the last column never triggers an auto-scroll/drift.
        display.setDelayLineWrap(true);
        display.resize(Math.max(1, terminal.getHeight()), Math.max(1, terminal.getWidth()));
        Attributes original = null;
        RuntimeException primaryFailure = null;
        try {
            terminal.writer().print(ENTER_ALTERNATE_SCREEN);
            terminal.writer().print(RESET_SCROLL_REGION);
            terminal.writer().flush();
            terminal.flush();
            original = terminal.enterRawMode();
            terminal.writer().print(ENABLE_MOUSE_TRACKING);
            terminal.writer().flush();
            terminal.flush();
            int totalLines = renderDocument(document, state).size();
            state = TranscriptOverlayModel.start(
                    foldable, thinking, retainedStates, pageSize, totalLines);
            while (!state.closed()) {
                display.update(render(document, state), -1, true);
                terminal.flush();
                TranscriptOverlayModel.Action action = TranscriptOverlayModel.decode(readKey());
                if (action != null) {
                    state = TranscriptOverlayModel.reduce(state, action);
                    if (action == TranscriptOverlayModel.Action.TOGGLE_THINKING) {
                        state = TranscriptOverlayModel.withTotalLines(
                                state, renderDocument(document, state).size());
                    }
                }
            }
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = null;
            try {
                terminal.writer().print(DISABLE_MOUSE_TRACKING);
                terminal.writer().flush();
                terminal.flush();
            } catch (RuntimeException failure) {
                cleanupFailure = failure;
            }
            if (original != null) {
                try {
                    terminal.setAttributes(original);
                } catch (RuntimeException failure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure;
                    } else {
                        cleanupFailure.addSuppressed(failure);
                    }
                }
            }
            try {
                terminal.writer().print(LEAVE_ALTERNATE_SCREEN);
                terminal.writer().flush();
                terminal.flush();
            } catch (RuntimeException failure) {
                if (cleanupFailure == null) {
                    cleanupFailure = failure;
                } else {
                    cleanupFailure.addSuppressed(failure);
                }
            }
            if (cleanupFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private List<AttributedString> render(Document document, TranscriptOverlayModel.State state) {
        List<AttributedString> documentLines = renderDocument(document, state);
        int contentRows = Math.max(0, terminal.getHeight() - 1);
        int start = Math.min(state.viewportStart(),
                Math.max(0, documentLines.size() - contentRows));
        int end = Math.min(documentLines.size(), start + contentRows);
        List<AttributedString> lines = new ArrayList<>(documentLines.subList(start, end));
        lines.add(new AttributedString(
                "wheel/j/k/↑/↓ line  PgUp/PgDn page  g/G ends  Ctrl+T thinking  Ctrl+O/q/ESC close"));
        return lines;
    }

    private List<AttributedString> renderDocument(Document document,
                                                  TranscriptOverlayModel.State state) {
        List<AttributedString> lines = new ArrayList<>();
        int width = Math.max(1, terminal.getWidth() - 2);
        for (DisplayEntry entry : document.entries()) {
            if (entry.blockIndex() < 0) {
                lines.add(new AttributedString("  > " + entry.prompt()));
                continue;
            }
            int blockIndex = entry.blockIndex();
            List<AttributedString> itemLines = document.blocks().get(blockIndex)
                    .render(width, state.foldStates().get(blockIndex));
            if (itemLines.isEmpty()) {
                lines.add(AttributedString.EMPTY);
                continue;
            }
            lines.add(prefixed("  ", itemLines.get(0)));
            for (int line = 1; line < itemLines.size(); line++) {
                lines.add(prefixed("  ", itemLines.get(line)));
            }
        }
        return lines;
    }

    private static AttributedString prefixed(String prefix, AttributedString content) {
        return new AttributedStringBuilder().append(prefix).append(content).toAttributedString();
    }

    private String readKey() {
        try {
            return readKey(terminal.reader());
        } catch (Exception ignored) {
            return "q";
        }
    }

    static String readKey(NonBlockingReader reader) throws java.io.IOException {
        int first = reader.read();
        if (first < 0) {
            return "q";
        }
        StringBuilder key = new StringBuilder().append((char) first);
        if (first != 27) {
            return key.toString();
        }
        while (true) {
            int next = reader.read(25);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) {
                return key.toString();
            }
            key.append((char) next);
            if (Character.isLetter(next) || next == '~') {
                return key.toString();
            }
        }
    }

    private static Document flatten(List<RetainedWindow.TurnView> turns) {
        List<DisplayEntry> entries = new ArrayList<>();
        List<StripEntry> blocks = new ArrayList<>();
        for (RetainedWindow.TurnView turn : turns == null ? List.<RetainedWindow.TurnView>of() : turns) {
            entries.add(new DisplayEntry(turn.userPrompt(), -1));
            for (StripEntry block : turn.blocks()) {
                int blockIndex = blocks.size();
                blocks.add(block);
                entries.add(new DisplayEntry("", blockIndex));
            }
        }
        return new Document(entries, blocks);
    }

    private record Document(List<DisplayEntry> entries, List<StripEntry> blocks) {
        Document {
            entries = List.copyOf(entries);
            blocks = List.copyOf(blocks);
        }
    }

    private record DisplayEntry(String prompt, int blockIndex) {
        DisplayEntry {
            prompt = prompt == null ? "" : prompt;
        }
    }
}
