package com.cafecli.app;

import com.cafecli.harness.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandCompositionTest {
    @TempDir Path root;
    @Test void interactiveCompositionRegistersEveryLegacyCommandFamilyAndSessionBuiltins() throws Exception {
        Files.writeString(root.resolve("script.json"),"{\"responses\":[{\"text\":\"unused\"}]}");
        var config=ConfigLoader.load(new ConfigSources(Map.of("cafecli.provider","fake","cafecli.fake.script","script.json"),Map.of(),root.resolve("home"),root));
        try (var session=Composition.interactiveSession(config,ignored -> {},ignored -> {})) {
            var names=session.commands().stream().map(com.cafecli.harness.SessionCommand::name).collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.of("/exit","/quit","/cancel","/clear","/transcript","/history","/model","/plan","/hitl",
                    "/mcp","/memory","/mem","/save","/index","/search","/graph","/context","/ctx","/policy","/config","/audit",
                    "/snapshot","/restore","/browser","/task","/skill","/hooks","/help"),names);
            assertTrue(session.dispatch("/policy").orElseThrow().contains(root.toString()));
            assertTrue(session.dispatch("/audit nope").orElseThrow().contains("今日尚无审计记录"));
            assertTrue(session.dispatch("/hooks").orElseThrow().contains("未加载任何生命周期 Hook"));
            assertTrue(session.dispatch("/search").orElseThrow().contains("请提供检索关键词"));
            assertTrue(session.dispatch("/graph").orElseThrow().contains("请提供类名"));
            assertTrue(session.log().isEmpty());
        }
    }
    @Test @org.junit.jupiter.api.Timeout(30)
    void explicitConfigChangesSelectNewTransportsWhileRetainedChildrenKeepTheirCapturedRouteAndHistory() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        record Request(String path,String authorization,com.fasterxml.jackson.databind.JsonNode body) {}
        var requests=new java.util.concurrent.CopyOnWriteArrayList<Request>();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/",exchange -> {
            var body=json.readTree(exchange.getRequestBody());
            requests.add(new Request(exchange.getRequestURI().getPath(),exchange.getRequestHeaders().getFirst("Authorization"),body));
            var messages=body.path("messages");
            var last=messages.get(messages.size()-1);
            String content=last.path("content").toString();
            Map<String,Object> delta;
            String finish;
            if (last.path("role").asText().equals("user") && (content.contains("MAKE_CHILD") || content.contains("CONTINUE_CHILD"))) {
                boolean spawn=content.contains("MAKE_CHILD");
                String arguments=json.writeValueAsString(spawn ? Map.of("description","captured child","prompt","CHILD_START")
                        : Map.of("to","task_1","message","CHILD_CONTINUE"));
                delta=Map.of("tool_calls",List.of(Map.of("index",0,"id",spawn ? "spawn" : "continue","type","function",
                        "function",Map.of("name",spawn ? "spawn_agent" : "send_message","arguments",arguments))));
                finish="tool_calls";
            } else { delta=Map.of("content","answer"); finish="stop"; }
            String event=json.writeValueAsString(Map.of("choices",List.of(Map.of("delta",delta,"finish_reason",finish))));
            byte[] response=("data: "+event+"\n\ndata: [DONE]\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            Path file=Files.createDirectories(root.resolve("home/.cafecli")).resolve("config.json");
            Files.writeString(file,json.writeValueAsString(Map.of("defaultProvider","step","keep",42,"providers",Map.of(
                    "step",Map.of("apiKey","old-key","baseUrl",base+"/old","model","step-router-v1")))));
            var config=ConfigLoader.load(new ConfigSources(Map.of("cafecli.snapshot.enabled","false","cafecli.lsp.enabled","false"),
                    Map.of(),root.resolve("home"),root));
            try (var session=Composition.headlessSession(config)) {
                session.submit("MAKE_CHILD");
                assertTrue(session.dispatch("/task").orElseThrow().contains("task_1"));
                var retained=session.log();
                assertTrue(session.dispatch("/config provider step --api-key new-key --base-url "+base+"/fresh --model step-router-v1 --default")
                        .orElseThrow().contains("已保存 provider 配置: step"));
                assertTrue(session.dispatch("/model step").orElseThrow().contains("step-router-v1"));
                assertTrue(session.model().providerRoute().startsWith("configured-"));
                assertEquals(session.model(),session.status().model());
                session.submit("CONTINUE_CHILD");
                assertEquals(retained,session.log().subList(0,retained.size()));
                var childContinuation=requests.stream().filter(request -> {
                    var messages=request.body().path("messages"); var last=messages.get(messages.size()-1);
                    return last.path("role").asText().equals("user") && last.path("content").toString().contains("CHILD_CONTINUE");
                }).findFirst().orElseThrow();
                assertEquals("/old/chat/completions",childContinuation.path());
                assertEquals("Bearer old-key",childContinuation.authorization());
                assertTrue(childContinuation.body().path("messages").toString().contains("CHILD_START"));
                var main=requests.stream().filter(request -> request.path().equals("/fresh/chat/completions")).findFirst().orElseThrow();
                assertEquals("Bearer new-key",main.authorization());
                assertTrue(main.body().path("messages").toString().contains("MAKE_CHILD"));
                assertFalse(main.body().path("messages").toString().contains("/config"));
                assertTrue(session.dispatch("/config provider freellmapi --api-key free-key --base-url "+base+"/free --model auto").isPresent());
                assertTrue(session.dispatch("/model freellmapi").orElseThrow().contains("auto (freellmapi)"));
                session.submit("newly configured provider");
                assertEquals("/free/chat/completions",requests.getLast().path());
                assertEquals("Bearer free-key",requests.getLast().authorization());
                assertEquals(42,json.readTree(file.toFile()).path("keep").asInt());
            }
        } finally { server.stop(0); }
    }
}
