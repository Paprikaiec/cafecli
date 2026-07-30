package com.cafecli.render.inline;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AnswerSegmentTest {

    @Test
    void completedProseRowsHighlightPathAndFunctionEntities() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        AnswerSegment answer = new AnswerSegment(terminal, () -> { });
        answer.append("See `src/Agent.java:232` and executeTools().\n");
        answer.finish();

        AttributedString line = answer.renderRetained(120).getFirst();

        assertNotEquals(AttributedStyle.DEFAULT,
                line.styleAt(line.toString().indexOf("src/Agent")));
        assertNotEquals(line.styleAt(line.toString().indexOf("src/Agent")),
                line.styleAt(line.toString().indexOf("executeTools")));
    }

    @Test
    void completedLineEntityPassRunsOnceAcrossRenderFrames() {
        Terminal terminal = Mockito.mock(Terminal.class);
        Mockito.when(terminal.getType()).thenReturn("dumb");
        AnswerSegment answer = new AnswerSegment(terminal, () -> { });
        answer.append("See src/Agent.java:232.\n");
        answer.finish();

        for (int frame = 0; frame < 100; frame++) {
            answer.renderRetained(120);
        }
        answer.renderRetained(80);

        assertEquals(1, answer.proseHighlightPasses());
    }
}
