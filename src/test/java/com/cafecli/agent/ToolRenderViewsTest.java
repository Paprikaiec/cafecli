package com.cafecli.agent;

import com.cafecli.llm.LlmClient;
import com.cafecli.render.ToolCallView;
import com.cafecli.render.ToolResultView;
import com.cafecli.tool.ToolExecutionResult;
import com.cafecli.tool.ToolOutput;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The v1 agent hands the renderer view types, not its own. Every view field is a String, so a swapped
 * field would still compile and every renderer test (which builds views directly) would still pass —
 * this pins the mapping itself.
 */
class ToolRenderViewsTest {

    @Test
    void aToolCallKeepsItsIdNameAndArgumentsInTheirOwnFields() {
        List<ToolCallView> views = ToolRenderViews.calls(List.of(
                new LlmClient.ToolCall("call-1",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}"))));

        assertEquals(List.of(new ToolCallView("call-1", "read_file", "{\"path\":\"a.txt\"}")), views);
    }

    @Test
    void aToolResultCarriesTheTextTheModelIsSent() {
        ToolExecutionResult result = new ToolExecutionResult("call-1", "read_file", "{\"path\":\"a.txt\"}",
                ToolOutput.text("hello"), 3, false);

        List<ToolResultView> views = ToolRenderViews.results(List.of(result));

        assertEquals(List.of(new ToolResultView("call-1", "read_file", "{\"path\":\"a.txt\"}", result.result())),
                views);
    }

    @Test
    void nullsPassThroughForTheRendererToHandleAsBefore() {
        assertNull(ToolRenderViews.calls(null));
        assertNull(ToolRenderViews.results(null));
        assertEquals(Arrays.asList((ToolResultView) null),
                ToolRenderViews.results(Arrays.asList((ToolExecutionResult) null)));
    }
}
