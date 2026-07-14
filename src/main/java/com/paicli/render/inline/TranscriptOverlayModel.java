package com.paicli.render.inline;

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
        TOGGLE,
        EXIT
    }

    record State(int cursor, int viewportStart, int pageSize, List<Boolean> foldable,
                 List<FoldState> foldStates, boolean closed) {
        State {
            pageSize = Math.max(1, pageSize);
            foldable = foldable == null ? List.of() : List.copyOf(foldable);
            foldStates = foldStates == null ? initialFoldStates(foldable) : List.copyOf(foldStates);
        }

        State(int cursor, int viewportStart, int pageSize, List<Boolean> foldable) {
            this(cursor, viewportStart, pageSize, foldable, null, false);
        }
    }

    private TranscriptOverlayModel() {
    }

    static State start(List<Boolean> foldable, int pageSize) {
        return new State(0, 0, pageSize, foldable);
    }

    static State start(List<Boolean> foldable, List<FoldState> foldStates, int pageSize) {
        return new State(0, 0, pageSize, foldable, foldStates, false);
    }

    static Action decode(String key) {
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
        if ("\r".equals(key) || "\n".equals(key)
                || String.valueOf((char) 15).equals(key)) {
            return Action.TOGGLE;
        }
        if ("q".equals(key) || "\u001b".equals(key)) {
            return Action.EXIT;
        }
        return "\u001b[6~".equals(key) || " ".equals(key) ? Action.PAGE_DOWN : null;
    }

    static State reduce(State state, Action action) {
        if (action == Action.EXIT) {
            return new State(state.cursor(), state.viewportStart(), state.pageSize(),
                    state.foldable(), state.foldStates(), true);
        }
        if (action == Action.TOGGLE
                && state.cursor() < state.foldable().size()
                && state.foldable().get(state.cursor())) {
            var next = new java.util.ArrayList<>(state.foldStates());
            next.set(state.cursor(), next.get(state.cursor()).toggle());
            return new State(state.cursor(), state.viewportStart(), state.pageSize(),
                    state.foldable(), next, state.closed());
        }
        if (action == Action.FIRST) {
            return stateAt(state, 0, 0);
        }
        if (action == Action.LAST && !state.foldable().isEmpty()) {
            int cursor = state.foldable().size() - 1;
            return stateAt(state, cursor, Math.max(0, cursor - state.pageSize() + 1));
        }
        if (action == Action.PAGE_UP && !state.foldable().isEmpty()) {
            int cursor = Math.max(0, state.cursor() - state.pageSize());
            return stateAt(state, cursor, cursor);
        }
        if (action == Action.PAGE_DOWN && !state.foldable().isEmpty()) {
            int cursor = Math.min(state.foldable().size() - 1,
                    state.cursor() + state.pageSize());
            return stateAt(state, cursor, cursor);
        }
        if (action == Action.UP && !state.foldable().isEmpty()) {
            int cursor = Math.max(0, state.cursor() - 1);
            return stateAt(state, cursor, Math.min(state.viewportStart(), cursor));
        }
        if (action != Action.DOWN || state.foldable().isEmpty()) {
            return state;
        }
        int cursor = Math.min(state.foldable().size() - 1, state.cursor() + 1);
        int viewportStart = Math.max(state.viewportStart(), cursor - state.pageSize() + 1);
        return stateAt(state, cursor, viewportStart);
    }

    private static State stateAt(State state, int cursor, int viewportStart) {
        return new State(cursor, viewportStart, state.pageSize(),
                state.foldable(), state.foldStates(), state.closed());
    }

    private static List<FoldState> initialFoldStates(List<Boolean> foldable) {
        return foldable.stream().map(ignored -> FoldState.COLLAPSED).toList();
    }
}
