package com.cafecli.ui;

import com.cafecli.ai.StreamEvent;
import com.cafecli.ai.message.*;
import com.cafecli.core.*;
import com.cafecli.core.log.ToolResultEntry;
import com.cafecli.render.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EventRenderAdapterTest {
    static final AgentEvent.Origin MAIN = new AgentEvent.Origin("main");
    @Test void finalBatchReplacesImmediateResultsAndKeepsSourceOrderAndCallMetadata() {
        RecordingRenderer renderer = new RecordingRenderer();
        var ui = new EventRenderAdapter(renderer, "fake", 1000);
        var a = new ToolCall("a", "read_file", "{\"path\":\"one\"}");
        var b = new ToolCall("b", "read_file", "{\"path\":\"two\"}");
        ui.accept(new AgentEvent.RunStart(MAIN));
        ui.accept(new AgentEvent.MessageEnd(MAIN, answer(List.of(a,b))));
        ui.accept(new AgentEvent.ToolEnd(MAIN, result("b", "raw two")));
        ui.accept(new AgentEvent.ToolEnd(MAIN, result("a", "raw one")));
        assertTrue(renderer.results.isEmpty());
        ui.accept(new AgentEvent.ToolBatchEnd(MAIN, List.of(result("a", "final one"), result("b", "final two"))));
        ui.accept(new AgentEvent.IterationEnd(MAIN, 1));
        assertEquals(List.of(List.of(new ToolResultView("a","read_file",a.arguments(),"final one"),
                new ToolResultView("b","read_file",b.arguments(),"final two"))), renderer.results);
    }
    @Test void finishingACompletedMessageDoesNotPrintTheTurnSeparatorTwice() {
        var renderer = new RecordingRenderer();
        var ui = new EventRenderAdapter(renderer,"fake",1000);
        ui.accept(new AgentEvent.RunStart(MAIN));
        ui.accept(new AgentEvent.MessageStart(MAIN));
        ui.accept(new AgentEvent.MessageUpdate(MAIN,new StreamEvent.TextDelta(0,"done\n")));
        var message = answer(List.of(new TextBlock("done\n")));
        ui.accept(new AgentEvent.MessageEnd(MAIN,message));
        ui.accept(new AgentEvent.RunEnd(MAIN,new RunOutcome.Completed(message)));
        assertEquals("done\n\n", renderer.bytes.toString(StandardCharsets.UTF_8));
    }

    @Test void ordinaryImmediateCompletionsStillPublishOneSourceOrderBatchAndIgnoreChildren() {
        var renderer = new RecordingRenderer();
        var ui = new EventRenderAdapter(renderer,"fake",1000);
        var first = new ToolCall("a","read_file","{\"path\":\"one\"}");
        var second = new ToolCall("b","read_file","{\"path\":\"two\"}");
        var child = new AgentEvent.Origin("child");
        ui.accept(new AgentEvent.MessageStart(child));
        ui.accept(new AgentEvent.MessageUpdate(child,new StreamEvent.TextDelta(0,"child answer")));
        ui.accept(new AgentEvent.MessageEnd(child,answer(List.of(new ToolCall("hidden","write_file","{}")))));
        ui.accept(new AgentEvent.ToolEnd(child,result("hidden","hidden result")));
        ui.accept(new AgentEvent.IterationEnd(child,1));
        ui.accept(new AgentEvent.RunStart(MAIN));
        ui.accept(new AgentEvent.MessageEnd(MAIN,answer(List.of(first,second))));
        ui.accept(new AgentEvent.ToolEnd(MAIN,result("b","second")));
        ui.accept(new AgentEvent.ToolEnd(MAIN,result("a","first")));
        ui.accept(new AgentEvent.IterationEnd(MAIN,1));
        ui.accept(new AgentEvent.IterationEnd(MAIN,1));
        assertEquals(List.of(List.of(new ToolCallView("a","read_file",first.arguments()),
                new ToolCallView("b","read_file",second.arguments()))),renderer.calls);
        assertEquals(List.of("first","second"),renderer.results.getFirst().stream().map(ToolResultView::text).toList());
        assertEquals(1,renderer.results.size());
        assertFalse(renderer.bytes.toString(StandardCharsets.UTF_8).contains("child answer"));
    }

    @Test void statusUsesMainUsageRetryAndCompactionEventsWithoutPrintingRetryNotices() {
        var renderer = new RecordingRenderer();
        var ui = new EventRenderAdapter(renderer,"fake",1000);
        ui.accept(new AgentEvent.RunStart(MAIN));
        ui.updateSessionStatus(new com.cafecli.harness.SessionStatus(new com.cafecli.ai.Model("fake","fake","fake",1000,100,Set.of()),
                55,0,0,0,null));
        ui.accept(new AgentEvent.MessageUpdate(MAIN,new StreamEvent.UsageUpdate(new Usage(100,20,30))));
        ui.accept(new AgentEvent.MessageEnd(MAIN,new AssistantMessage(List.of(new TextBlock("first")),StopReason.END_TURN,
                new Usage(100,20,30),"fake","fake")));
        var tokens = renderer.statuses.getLast();
        assertEquals(55,tokens.totalTokens()); assertEquals(100,tokens.inputTokens());
        assertEquals(20,tokens.outputTokens()); assertEquals(30,tokens.cachedInputTokens());
        ui.accept(new AgentEvent.Retry(new AgentEvent.Origin("child"),new StreamEvent.Retry(2,3,"hidden")));
        assertEquals(tokens,renderer.statuses.getLast());
        ui.accept(new AgentEvent.Retry(MAIN,new StreamEvent.Retry(2,3,"busy")));
        assertEquals("retry 2/3",renderer.statuses.getLast().phase());
        ui.accept(new AgentEvent.Compaction(MAIN,0,2));
        assertEquals("compacting",renderer.statuses.getLast().phase());
        assertEquals("",renderer.bytes.toString(StandardCharsets.UTF_8));
        ui.accept(new AgentEvent.MessageUpdate(MAIN,new StreamEvent.UsageUpdate(new Usage(200,40,50))));
        ui.accept(new AgentEvent.MessageEnd(MAIN,new AssistantMessage(List.of(new TextBlock("second")),StopReason.END_TURN,
                new Usage(200,40,50),"fake","fake")));
        var cumulative = renderer.statuses.getLast();
        assertEquals(300,cumulative.inputTokens()); assertEquals(60,cumulative.outputTokens());
        assertEquals(80,cumulative.cachedInputTokens()); assertEquals("¥0.0021",cumulative.estimatedCost());
        assertEquals(55,cumulative.totalTokens());
    }

    @Test void commandProgressAndFinalDiffUseTheirTypedRenderChannels() throws Exception {
        var renderer = new RecordingRenderer();
        var ui = new EventRenderAdapter(renderer,"fake",1000);
        var call = new ToolCall("write","write_file","{\"path\":\"note.txt\"}");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        ui.accept(new AgentEvent.RunStart(MAIN));
        ui.accept(new AgentEvent.MessageEnd(MAIN,answer(List.of(call))));
        ui.accept(new AgentEvent.ToolUpdate(MAIN,new ToolCall("cmd","execute_command","{}"),
                new ToolResult(List.of(new TextBlock("running")),json.readTree("{\"command\":\"build\",\"line\":\"running\"}"),ToolResult.Status.OK)));
        ui.accept(new AgentEvent.ToolEnd(MAIN,new ToolResultEntry(new ToolResultMessage("write","write_file",List.of(new TextBlock("written")),false),
                ToolResult.Status.OK,json.readTree("{\"diff\":{\"path\":\"note.txt\",\"before\":null,\"after\":\"new\"}}"))));
        assertEquals(List.of("build:running"),renderer.progress);
        assertTrue(renderer.diffs.isEmpty());
        ui.accept(new AgentEvent.IterationEnd(MAIN,1));
        assertEquals(List.of("note.txt:null:new"),renderer.diffs);
        assertEquals("write_file",renderer.results.getFirst().getFirst().name());
    }

    @Test void idleDockKeepsContextButClearsRunUsageAndCost() {
        var renderer = new RecordingRenderer();
        var model = new com.cafecli.ai.Model("fake","fake","fake",1000,100,Set.of());
        var ui = new EventRenderAdapter(renderer,new com.cafecli.harness.SessionStatus(model,55,300,60,80,"¥0.0021"));
        ui.accept(new AgentEvent.RunStart(MAIN));
        ui.updateSessionStatus(new com.cafecli.harness.SessionStatus(model,77,300,60,80,"¥0.0021"));
        assertEquals(300,renderer.statuses.getLast().inputTokens());
        ui.accept(new AgentEvent.RunEnd(MAIN,new RunOutcome.Completed(answer(List.of()))));
        var idle = renderer.statuses.getLast();
        assertEquals("idle",idle.phase()); assertEquals(77,idle.totalTokens());
        assertEquals(0,idle.inputTokens()); assertEquals(0,idle.outputTokens());
        assertEquals(0,idle.cachedInputTokens()); assertNull(idle.estimatedCost());
    }

    static AssistantMessage answer(List<AssistantContent> content) {
        return new AssistantMessage(content, StopReason.END_TURN, Usage.ZERO, "fake", "fake");
    }
    static ToolResultEntry result(String id, String text) {
        return new ToolResultEntry(new ToolResultMessage(id,"read_file",List.of(new TextBlock(text)),false));
    }
    static class RecordingRenderer implements Renderer {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(bytes,true,StandardCharsets.UTF_8);
        final List<List<ToolResultView>> results = new ArrayList<>();
        final List<StatusInfo> statuses = new ArrayList<>();
        final List<List<ToolCallView>> calls = new ArrayList<>();
        final List<String> diffs = new ArrayList<>();
        final List<String> progress = new ArrayList<>();
        public void start() {} public void close() {} public PrintStream stream(){ return out; }
        public void appendToolCalls(List<ToolCallView> c){ calls.add(c); }
        public void appendToolResults(List<ToolResultView> r){results.add(r);}
        public void appendDiff(String p,String b,String a){diffs.add(p+":"+b+":"+a);}
        public void appendCommandOutput(String c,String l){progress.add(c+":"+l);}
        public void updateStatus(StatusInfo s){statuses.add(s);}
        public com.cafecli.hitl.ApprovalResult promptApproval(com.cafecli.hitl.ApprovalRequest r){throw new UnsupportedOperationException();}
        public int openPalette(String t,List<String> i){return -1;}
    }
}
