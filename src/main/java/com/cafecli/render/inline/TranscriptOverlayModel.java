package com.cafecli.render.inline;

import java.util.List;

/** Pure immutable navigation state for the retained-transcript overlay. */
final class TranscriptOverlayModel {

    enum Action {
        DOWN,
        UP,
        PAGE_DOWN,
        PAGE_UP,
        FIRST,
        LAST,
        TOGGLE_THINKING,
        EXIT
    }

    record State(int viewportStart, int pageSize, int totalLines,
                 List<FoldState> foldStates, List<Boolean> thinking,
                 boolean thinkingExpanded, boolean closed) {
        State {
            viewportStart = Math.max(0, viewportStart);
            pageSize = Math.max(1, pageSize);
            totalLines = Math.max(0, totalLines);
            foldStates = foldStates == null ? List.of() : List.copyOf(foldStates);
            thinking = thinking == null ? List.of() : List.copyOf(thinking);
        }
    }

    private TranscriptOverlayModel() {
    }

    static State start(List<Boolean> foldable, List<Boolean> thinking, int pageSize) {
        return start(foldable, thinking, List.of(), pageSize);
    }

    static State start(List<Boolean> foldable, List<Boolean> thinking,
                       List<FoldState> foldStates, int pageSize) {
        List<Boolean> safeFoldable = foldable == null ? List.of() : List.copyOf(foldable);
        return start(foldable, thinking, foldStates, pageSize, safeFoldable.size());
    }

    static State start(List<Boolean> foldable, List<Boolean> thinking,
                       List<FoldState> foldStates, int pageSize, int totalLines) {
        List<Boolean> safeFoldable = foldable == null ? List.of() : List.copyOf(foldable);
        List<Boolean> safeThinking = thinking == null ? List.of() : List.copyOf(thinking);
        List<FoldState> safeStates = foldStates == null ? List.of() : List.copyOf(foldStates);
        return new State(0, pageSize, totalLines,
                overlayFoldStates(safeFoldable, safeThinking, safeStates, false),
                safeThinking, false, false);
    }

    /**
     * Re-derives the fold choices after the session committed more items while the overlay was open
     * (ADR 0010 amendment A13), keeping whatever Ctrl+T has been set to.
     *
     * <p>Fold choice is a function of what an item <em>is</em>, not of when it arrived, so this is the
     * same rule {@link #start} applies — applied again to a longer log.
     */
    static State withItems(State state, List<Boolean> foldable, List<Boolean> thinking,
                           List<FoldState> retainedStates) {
        List<Boolean> safeFoldable = foldable == null ? List.of() : List.copyOf(foldable);
        List<Boolean> safeThinking = thinking == null ? List.of() : List.copyOf(thinking);
        List<FoldState> safeStates = retainedStates == null ? List.of() : List.copyOf(retainedStates);
        return new State(state.viewportStart(), state.pageSize(), state.totalLines(),
                overlayFoldStates(safeFoldable, safeThinking, safeStates, state.thinkingExpanded()),
                safeThinking, state.thinkingExpanded(), state.closed());
    }

    /** Re-anchors {@code totalLines} after a fold change without disturbing the viewport. */
    static State withTotalLines(State state, int totalLines) {
        return new State(state.viewportStart(), state.pageSize(), totalLines,
                state.foldStates(), state.thinking(), state.thinkingExpanded(), state.closed());
    }

    /**
     * Re-anchors the page size after the terminal was resized while the overlay was open (ADR 0010
     * amendment A12). The viewport keeps its row, so the user stays where they were reading.
     */
    static State withPageSize(State state, int pageSize) {
        return new State(state.viewportStart(), pageSize, state.totalLines(),
                state.foldStates(), state.thinking(), state.thinkingExpanded(), state.closed());
    }

    /**
     * Whether the viewport is showing the end of the document — the [[转写浏览层]]'s "following"
     * state (ADR 0010 amendment A13).
     *
     * <p>It is <b>derived</b> rather than stored, which is what makes returning to the tail resume
     * following without anything having to notice that it happened: the position is the mode.
     */
    static boolean pinnedToTail(State state) {
        return state.viewportStart() >= lastViewportStart(state);
    }

    /**
     * Take the document's new height after output arrived while the overlay was open.
     *
     * <p>Pinned at the tail the viewport advances with it; scrolled up it holds its row, so browsing is
     * never yanked. This is the growth path only — the measurement path is {@link #withTotalLines},
     * which never moves the viewport, because measuring further into a document the user has not
     * reached is not new output.
     */
    static State follow(State state, int totalLines) {
        boolean following = pinnedToTail(state);
        State grown = withTotalLines(state, totalLines);
        return following ? withViewportStart(grown, lastViewportStart(grown)) : grown;
    }

    private static int lastViewportStart(State state) {
        return Math.max(0, state.totalLines() - state.pageSize());
    }

    /** Moves the viewport without any other change — used to re-clamp it after a resize. */
    static State withViewportStart(State state, int viewportStart) {
        return new State(viewportStart, state.pageSize(), state.totalLines(),
                state.foldStates(), state.thinking(), state.thinkingExpanded(), state.closed());
    }

    static Action decode(String key) {
        Action mouseAction = decodeSgrMouse(key);
        if (mouseAction != null) {
            return mouseAction;
        }
        if ("j".equals(key) || "\u001b[B".equals(key)) {
            return Action.DOWN;
        }
        if ("k".equals(key) || "\u001b[A".equals(key)) {
            return Action.UP;
        }
        if ("\u001b[5~".equals(key)) {
            return Action.PAGE_UP;
        }
        if ("g".equals(key)) {
            return Action.FIRST;
        }
        if ("G".equals(key)) {
            return Action.LAST;
        }
        if (String.valueOf((char) 20).equals(key)) {
            return Action.TOGGLE_THINKING;
        }
        if ("q".equals(key) || "\u001b".equals(key)
                || String.valueOf((char) 15).equals(key)) {
            return Action.EXIT;
        }
        return "\u001b[6~".equals(key) || " ".equals(key) ? Action.PAGE_DOWN : null;
    }

    static Action decodeSgrMouse(String sequence) {
        if (sequence == null || !sequence.startsWith("\u001b[<")
                || !(sequence.endsWith("M") || sequence.endsWith("m"))) {
            return null;
        }
        String[] fields = sequence.substring(3, sequence.length() - 1).split(";", -1);
        if (fields.length != 3) {
            return null;
        }
        try {
            int button = Integer.parseInt(fields[0]);
            Integer.parseInt(fields[1]);
            Integer.parseInt(fields[2]);
            int withoutModifiers = button & ~(4 | 8 | 16);
            return switch (withoutModifiers) {
                case 64 -> Action.UP;
                case 65 -> Action.DOWN;
                default -> null;
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static State reduce(State state, Action action) {
        if (action == Action.EXIT) {
            return new State(state.viewportStart(), state.pageSize(), state.totalLines(),
                    state.foldStates(), state.thinking(), state.thinkingExpanded(), true);
        }
        if (action == Action.TOGGLE_THINKING) {
            return toggleThinking(state);
        }
        if (action == Action.FIRST) {
            return stateAt(state, 0);
        }
        if (action == Action.LAST && state.totalLines() > 0) {
            return stateAt(state, Math.max(0, state.totalLines() - state.pageSize()));
        }
        if (action == Action.PAGE_UP && state.totalLines() > 0) {
            int viewportStart = Math.max(0, state.viewportStart() - state.pageSize());
            return stateAt(state, viewportStart);
        }
        if (action == Action.PAGE_DOWN && state.totalLines() > 0) {
            int viewportStart = Math.min(Math.max(0, state.totalLines() - state.pageSize()),
                    state.viewportStart() + state.pageSize());
            return stateAt(state, viewportStart);
        }
        if (action == Action.UP && state.totalLines() > 0) {
            int viewportStart = Math.max(0, state.viewportStart() - 1);
            return stateAt(state, viewportStart);
        }
        if (action != Action.DOWN || state.totalLines() == 0) {
            return state;
        }
        int viewportStart = Math.min(Math.max(0, state.totalLines() - state.pageSize()),
                state.viewportStart() + 1);
        return stateAt(state, viewportStart);
    }

    private static State toggleThinking(State state) {
        boolean expanded = !state.thinkingExpanded();
        List<FoldState> foldStates = state.foldStates();
        List<Boolean> thinking = state.thinking();
        FoldState revealed = expanded ? FoldState.EXPANDED : FoldState.COLLAPSED;
        List<FoldState> recomputed = java.util.stream.IntStream.range(0, foldStates.size())
                .mapToObj(index -> index < thinking.size() && thinking.get(index)
                        ? revealed : foldStates.get(index))
                .toList();
        return new State(state.viewportStart(), state.pageSize(), state.totalLines(),
                recomputed, thinking, expanded, state.closed());
    }

    private static State stateAt(State state, int viewportStart) {
        return new State(viewportStart, state.pageSize(), state.totalLines(),
                state.foldStates(), state.thinking(), state.thinkingExpanded(), state.closed());
    }

    private static List<FoldState> overlayFoldStates(List<Boolean> foldable,
                                                     List<Boolean> thinking,
                                                     List<FoldState> retainedStates,
                                                     boolean thinkingExpanded) {
        return java.util.stream.IntStream.range(0, foldable.size())
                .mapToObj(index -> {
                    if (!foldable.get(index)) {
                        return index < retainedStates.size()
                                ? retainedStates.get(index) : FoldState.COLLAPSED;
                    }
                    if (index < thinking.size() && thinking.get(index)) {
                        return thinkingExpanded ? FoldState.EXPANDED : FoldState.COLLAPSED;
                    }
                    return FoldState.EXPANDED;
                })
                .toList();
    }
}
