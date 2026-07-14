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

    private final Terminal terminal;

    TranscriptOverlay(Terminal terminal) {
        this.terminal = terminal;
    }

    void open(List<RetainedWindow.TurnView> turns) {
        Document document = flatten(turns);
        int pageSize = Math.max(1, terminal.getHeight() - 2);
        TranscriptOverlayModel.State state = TranscriptOverlayModel.start(
                document.blocks().stream().map(StripEntry::isFoldable).toList(),
                document.blocks().stream().map(StripEntry::foldState).toList(), pageSize);
        Display display = new Display(terminal, true);
        display.resize(Math.max(1, terminal.getHeight()), Math.max(1, terminal.getWidth()));
        Attributes original = null;
        RuntimeException primaryFailure = null;
        try {
            terminal.writer().print(ENTER_ALTERNATE_SCREEN);
            terminal.writer().flush();
            terminal.flush();
            original = terminal.enterRawMode();
            while (!state.closed()) {
                display.update(render(document, state), -1, true);
                terminal.flush();
                TranscriptOverlayModel.Action action = TranscriptOverlayModel.decode(readKey());
                if (action != null) {
                    state = TranscriptOverlayModel.reduce(state, action);
                }
            }
            persistFoldStates(document.blocks(), state);
        } catch (RuntimeException failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            RuntimeException cleanupFailure = null;
            if (original != null) {
                try {
                    terminal.setAttributes(original);
                } catch (RuntimeException failure) {
                    cleanupFailure = failure;
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
        List<AttributedString> lines = new ArrayList<>();
        int width = Math.max(1, terminal.getWidth() - 2);
        int contentRows = Math.max(0, terminal.getHeight() - 1);
        int start = displayStart(document.entries(), state.cursor(), document.blocks().isEmpty());
        for (int index = start; index < document.entries().size(); index++) {
            if (lines.size() >= contentRows) {
                break;
            }
            DisplayEntry entry = document.entries().get(index);
            if (entry.blockIndex() < 0) {
                lines.add(new AttributedString("  > " + entry.prompt()));
                continue;
            }
            int blockIndex = entry.blockIndex();
            String marker = blockIndex == state.cursor() ? "› " : "  ";
            List<AttributedString> itemLines = document.blocks().get(blockIndex)
                    .render(width, state.foldStates().get(blockIndex));
            if (itemLines.isEmpty()) {
                lines.add(new AttributedString(marker));
                continue;
            }
            lines.add(prefixed(marker, itemLines.get(0)));
            for (int line = 1; line < itemLines.size() && lines.size() < contentRows; line++) {
                lines.add(prefixed("  ", itemLines.get(line)));
            }
        }
        lines.add(new AttributedString("j/k move  Enter/Ctrl+O fold  PgUp/PgDn page  g/G ends  q/ESC close"));
        return lines;
    }

    private static int displayStart(List<DisplayEntry> entries, int cursor, boolean noBlocks) {
        if (noBlocks) {
            return 0;
        }
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).blockIndex() == cursor) {
                return index > 0 && entries.get(index - 1).blockIndex() < 0 ? index - 1 : index;
            }
        }
        return 0;
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

    private static void persistFoldStates(List<StripEntry> blocks,
                                          TranscriptOverlayModel.State state) {
        for (int index = 0; index < blocks.size(); index++) {
            if (blocks.get(index).isFoldable()) {
                blocks.get(index).persistFoldState(state.foldStates().get(index));
            }
        }
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
