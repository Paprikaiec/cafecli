package com.cafecli.render.inline;

import com.cafecli.llm.LlmClient;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule itself: <b>an entry retained in the ordered commit log is immutable source</b>
 * (ADR 0016 D1). Rendering it for a retained viewer mutates nothing on it, so it does not matter who
 * is reading — the [[活区]] on the render thread, the [[转写浏览层]] on the input thread, or both at
 * once.
 *
 * <p>This exists because the premise the previous design rested on — "safe because nothing else
 * mutates after commit" — was not checkable at review time. It is now: the assertion is a property of
 * the entry alone, with no reader in it, and the negative control below shows it has teeth.
 */
class StripEntryContractTest {

    /**
     * Entries that are never committed, so they never enter the log and are never read by a second
     * view: the spinner and the [[审批应答]] card, which live and die inside the [[活区]]; and
     * {@link LiveAnswer}, which <b>is</b> the [[活区]]'s view rather than an entry.
     */
    private static final Set<String> NEVER_RETAINED =
            Set.of("ActivityBlock", "ApprovalBlock", "LiveAnswer");

    private static Terminal terminal() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("xterm-256color");
        return terminal;
    }

    /** One instance of every kind of entry the ordered commit log can hold. */
    private static Map<String, StripEntry> retainedEntryKinds() {
        Terminal terminal = terminal();
        Map<String, StripEntry> kinds = new java.util.LinkedHashMap<>();
        kinds.put("FrozenTextItem", FrozenTextItem.ofAnsi("a background task finished\n"));
        kinds.put("SubmittedPromptItem",
                SubmittedPromptItem.of(terminal, "explain what changed in this turn"));
        kinds.put("LiveFoldableBlock", new LiveFoldableBlock(terminal,
                List.of(new AttributedString("collapsed")),
                List.of(new AttributedString("expanded and rather longer than the width")), false));
        kinds.put("ReasoningBlock", new ReasoningBlock(terminal, "weigh it\nread it\ndecide\n"));
        kinds.put("CodeBlock", new CodeBlock(terminal, "java", "int value = compute(1, 2);\n"));
        kinds.put("ToolCallBlock", completedToolBatch(terminal));
        kinds.put("DiffBlock", new DiffBlock(terminal, "src/Agent.java", "one\ntwo\n", "one\nthree\n"));
        kinds.put("AnswerSegment", streamedAnswer(terminal));
        // A position in the ordered commit log (ADR 0016 D5). It is what the log actually holds, so it
        // has to obey the same rule: laying a position out mutates neither the position nor the source
        // underneath it. The answer-backed one is the interesting case — it is the only source that
        // occupies more than one position.
        kinds.put("CommittedSpan", new CommittedSpan(streamedAnswer(terminal), 0));
        return kinds;
    }

    /**
     * A finished batch carrying <b>both</b> kinds of result view: a diff, which is what a file-writing
     * tool's result is (ADR 0017 D4), and the ordinary text head of the tool beside it. A batch with
     * only a header would leave the diff view unchecked by the rule below, and a {@link DiffBlock}
     * reached through another entry is exactly where a shared cache would hide.
     */
    private static ToolCallBlock completedToolBatch(Terminal terminal) {
        ToolCallBlock batch = new ToolCallBlock(terminal, List.of(
                new LlmClient.ToolCall("call-1",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"src/Agent.java\"}")),
                new LlmClient.ToolCall("call-2",
                        new LlmClient.ToolCall.Function("edit_file", "{\"path\":\"src/Agent.java\"}"))));
        batch.attachDiff(new DiffBlock(terminal, "src/Agent.java", "one\ntwo\n", "one\nthree\n"));
        batch.completeWith(List.of(
                new com.cafecli.tool.ToolExecutionResult("call-1", "read_file",
                        "{\"path\":\"src/Agent.java\"}",
                        com.cafecli.tool.ToolOutput.text("first line\nsecond line\nthird line\n"),
                        4, false),
                new com.cafecli.tool.ToolExecutionResult("call-2", "edit_file",
                        "{\"path\":\"src/Agent.java\"}",
                        com.cafecli.tool.ToolOutput.text("文件已编辑: src/Agent.java，共 1 条编辑"),
                        4, false)));
        return batch;
    }

    private static AnswerSegment streamedAnswer(Terminal terminal) {
        AnswerSegment answer = new AnswerSegment(terminal, () -> { });
        answer.append("a paragraph with src/Agent.java:12 in it, long enough to fold somewhere.\n");
        answer.append("| a | b |\n|---|---|\n| one | two |\n\n");
        answer.append("a trailing fragment still streaming");
        return answer;
    }

    /**
     * The structural half: the interface itself no longer offers anywhere to put a shared cache.
     * {@code releaseRenderCache()} / {@code renderCacheEntries()} were the two methods through which a
     * view reached into an entry it did not own.
     */
    @Test
    void theEntryInterfaceCarriesNoRenderCache() {
        List<String> cacheMethods = Stream.of(StripEntry.class.getDeclaredMethods())
                .map(Method::getName)
                .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("cache"))
                .filter(name -> !name.equals("newRendering"))
                .sorted()
                .toList();

        assertEquals(List.of(), cacheMethods,
                "rendering caches belong to the view (ADR 0016 D1), so StripEntry carries none");
    }

    /** Every view gets its own rendering; no two views can ever be handed the same state. */
    @Test
    void everyViewGetsItsOwnRendering() {
        retainedEntryKinds().forEach((kind, entry) -> {
            EntryRendering one = entry.newRendering();
            EntryRendering another = entry.newRendering();
            assertTrue(one != another, kind + " handed two views the same rendering");
            one.rows(80, FoldState.EXPANDED);
            assertEquals(0, another.cachedRows(),
                    kind + ": one view's layout filled another view's cache");
        });
    }

    /**
     * The rule. Laying an entry out for a retained viewer leaves the entry exactly as it was — every
     * field, recursively, including collection and map sizes.
     */
    @Test
    void renderingARetainedEntryMutatesNothingOnIt() {
        retainedEntryKinds().forEach((kind, entry) -> assertPureRetainedRender(kind, entry));
    }

    /**
     * The negative control, which is what makes the test above worth having: an entry that gains
     * mutable-after-commit state is caught, with no reader involved and nothing about threads.
     */
    @Test
    void theRuleCatchesAnEntryThatGainsMutableStateAfterCommit() {
        StripEntry cachesItsOwnRows = new StripEntry() {
            private final Map<Integer, List<AttributedString>> rowsByWidth = new HashMap<>();

            @Override
            public List<AttributedString> render(int width) {
                return rowsByWidth.computeIfAbsent(width,
                        columns -> List.of(new AttributedString("row at " + columns)));
            }

            @Override
            public List<AttributedString> freezeTopRows(int minimumRows, int width) {
                return List.of();
            }
        };

        AssertionError caught = assertThrows(AssertionError.class,
                () -> assertPureRetainedRender("a block that memoizes its own rows", cachesItsOwnRows));
        assertTrue(caught.getMessage().contains("a block that memoizes its own rows"));
    }

    /**
     * Every {@link StripEntry} in the package is either covered by the rule above or explicitly
     * excluded from it. Without this, a new entry type would ship unchecked and the rule would quietly
     * become "the entries somebody remembered".
     */
    @Test
    void everyEntryTypeIsEitherCheckedOrExplicitlyExcluded() throws Exception {
        Set<String> declared = new TreeSet<>(stripEntryClassNamesInPackage());
        Set<String> accountedFor = new TreeSet<>(retainedEntryKinds().keySet());
        accountedFor.addAll(NEVER_RETAINED);

        assertEquals(accountedFor, declared,
                "a StripEntry implementation must be checked by this test or listed in NEVER_RETAINED");
    }

    private static List<String> stripEntryClassNamesInPackage() throws IOException {
        Path classes = Path.of(java.util.Objects.requireNonNull(
                        StripEntry.class.getProtectionDomain().getCodeSource())
                .getLocation().getPath());
        Path packageDir = classes.resolve("com/cafecli/render/inline");
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(packageDir)) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".class") || fileName.contains("$")) {
                    continue;
                }
                String simple = fileName.substring(0, fileName.length() - ".class".length());
                Class<?> candidate;
                try {
                    candidate = Class.forName("com.cafecli.render.inline." + simple);
                } catch (Throwable unloadable) {
                    continue;
                }
                if (StripEntry.class.isAssignableFrom(candidate)
                        && !candidate.isInterface()
                        && !Modifier.isAbstract(candidate.getModifiers())) {
                    names.add(simple);
                }
            }
        }
        return names;
    }

    private static void assertPureRetainedRender(String kind, StripEntry entry) {
        String before = fingerprint(entry);
        for (int width : new int[]{40, 118}) {
            for (FoldState fold : FoldState.values()) {
                entry.renderRetained(width, fold);
                entry.isReplaySealed();
                entry.isStillCommitting();
                entry.isFoldable();
                entry.isThinking();
            }
        }
        String after = fingerprint(entry);
        if (!before.equals(after)) {
            throw new AssertionError(kind + ": rendering it for a retained viewer changed it."
                    + "\n  before: " + before + "\n  after:  " + after);
        }
    }

    /** A recursive value fingerprint: enough to see a map grow, a list grow or a counter move. */
    private static String fingerprint(Object value) {
        StringBuilder out = new StringBuilder();
        fingerprint(value, out, 0, new IdentityHashMap<>());
        return out.toString();
    }

    private static void fingerprint(Object value, StringBuilder out, int depth,
                                    IdentityHashMap<Object, Boolean> seen) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof CharSequence || value instanceof Enum<?>) {
            out.append(value);
            return;
        }
        if (value instanceof Collection<?> collection) {
            out.append("[size=").append(collection.size());
            if (depth < 4) {
                for (Object element : collection) {
                    out.append(',');
                    fingerprint(element, out, depth + 1, seen);
                }
            }
            out.append(']');
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.append("{size=").append(map.size()).append('}');
            return;
        }
        Class<?> type = value.getClass();
        if (!type.getName().startsWith("com.cafecli.") || type.isSynthetic() || depth >= 4) {
            // Terminals, JLine styles, lambdas: not ours, and never the thing that grows.
            out.append('<').append(type.getSimpleName()).append('>');
            return;
        }
        if (seen.put(value, Boolean.TRUE) != null) {
            out.append("<cycle>");
            return;
        }
        out.append(type.getSimpleName()).append('(');
        for (Class<?> level = type; level != null && level != Object.class;
                level = level.getSuperclass()) {
            for (Field field : level.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                field.setAccessible(true);
                out.append(field.getName()).append('=');
                try {
                    fingerprint(field.get(value), out, depth + 1, seen);
                } catch (IllegalAccessException unreadable) {
                    out.append("<unreadable>");
                }
                out.append(';');
            }
        }
        out.append(')');
    }
}
