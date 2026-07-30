package com.cafecli.render.inline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Bounded source-model window for the most recent user turns. */
final class RetainedWindow {

    private static final int MAX_TURNS = 5;

    private final Deque<Turn> turns = new ArrayDeque<>();
    private Turn current;
    private int releasedRenderCaches;

    synchronized List<StripEntry> beginTurn() {
        current = new Turn();
        turns.addLast(current);
        List<StripEntry> evicted = new ArrayList<>();
        while (turns.size() > MAX_TURNS) {
            Turn oldest = turns.removeFirst();
            evicted.addAll(oldest.blocks);
            release(oldest);
        }
        return List.copyOf(evicted);
    }

    synchronized void setPrompt(String prompt) {
        ensureCurrent().prompt = prompt == null ? "" : prompt;
    }

    synchronized void add(StripEntry block) {
        if (block != null) {
            ensureCurrent().blocks.add(block);
        }
    }

    synchronized List<InlineRenderer.RetainedTurnSnapshot> snapshot() {
        return turns.stream()
                .map(turn -> new InlineRenderer.RetainedTurnSnapshot(
                        turn.prompt, turn.blocks.size()))
                .toList();
    }

    synchronized List<TurnView> views() {
        return turns.stream()
                .map(turn -> new TurnView(turn.prompt, turn.blocks))
                .toList();
    }

    synchronized int renderCacheEntries() {
        return turns.stream()
                .flatMap(turn -> turn.blocks.stream())
                .mapToInt(StripEntry::renderCacheEntries)
                .sum();
    }

    synchronized int releasedRenderCaches() {
        return releasedRenderCaches;
    }

    synchronized void clear() {
        for (Turn turn : turns) {
            release(turn);
        }
        turns.clear();
        current = null;
        releasedRenderCaches = 0;
    }

    private void release(Turn turn) {
        for (StripEntry block : turn.blocks) {
            if (block.releaseRenderCache() > 0) {
                releasedRenderCaches++;
            }
        }
    }

    private Turn ensureCurrent() {
        if (current == null) {
            beginTurn();
        }
        return current;
    }

    private static final class Turn {
        private String prompt = "";
        private final List<StripEntry> blocks = new ArrayList<>();
    }

    record TurnView(String userPrompt, List<StripEntry> blocks) {
        TurnView {
            userPrompt = userPrompt == null ? "" : userPrompt;
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }
    }
}
