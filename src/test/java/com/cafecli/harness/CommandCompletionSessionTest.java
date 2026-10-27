package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.*;
import com.cafecli.harness.config.*;
import com.cafecli.input.*;
import com.cafecli.mcp.resources.McpResourceDescriptor;
import com.cafecli.skill.Skill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandCompletionSessionTest {
    @TempDir Path root;
    @Test void onlyRegisteredCommandFamiliesAreSuggestedIncludingACustomCapabilityCommand() {
        try (var session=session(List.of("/model","/browser","/search","/custom"))) {
            var items=complete(session,"/","/");
            assertEquals(Set.of("/model","/browser","/search","/custom"),items.stream()
                    .map(item -> item.value().split(" ")[0]).collect(java.util.stream.Collectors.toSet()));
            assertTrue(items.stream().anyMatch(item -> item.display().equals("/browser connect")));
            assertTrue(items.stream().anyMatch(item -> item.display().equals("/search <查询>")));
            assertTrue(items.stream().anyMatch(item -> item.value().equals("/custom")));
            assertTrue(complete(session,"hello","hello").isEmpty());
        }
    }
    @Test void subcommandsProvidersAndDynamicServerAndSkillNamesKeepLegacyReplacementValues() {
        try (var session=session(List.of("/model","/config","/mcp","/skill","/task","/browser","/snapshot"))) {
            assertTrue(complete(session,"/mcp r","r").stream().anyMatch(item -> item.value().equals("restart ")
                    && item.display().equals("/mcp restart <name>")));
            assertTrue(complete(session,"/model st","st").stream().anyMatch(item -> item.value().equals("step")));
            assertTrue(complete(session,"/config provider fr","fr").stream().anyMatch(item -> item.value().equals("freellmapi ")));
            assertTrue(complete(session,"/mcp logs ch","ch").stream().anyMatch(item -> item.value().equals("chrome-devtools")
                    && item.group().equals("MCP server")));
            assertTrue(complete(session,"/skill show web","web").stream().anyMatch(item -> item.value().equals("web-access")
                    && item.description().equals("浏览器和联网策略")));
            assertTrue(complete(session,"/skill sh","sh").stream().anyMatch(item -> item.value().equals("show ")));
            assertTrue(complete(session,"/task ca","ca").stream().anyMatch(item -> item.value().equals("cancel ")));
            assertTrue(complete(session,"/browser ta","ta").stream().anyMatch(item -> item.value().equals("tabs")));
            assertTrue(complete(session,"/snapshot st","st").stream().anyMatch(item -> item.value().equals("status")));
        }
    }
    @Test void ordinaryPathAndImageMentionsCompleteAgainstTheCapturedDirectory() throws Exception {
        Files.writeString(root.resolve("pom.xml"),"fixture");
        try (var session=session(List.of("/mcp"))) {
            assertTrue(complete(session,"@pom","@pom").stream().anyMatch(item -> item.value().equals("@pom.xml")));
            assertTrue(complete(session,"@image:pom","@image:pom").stream().anyMatch(item -> item.value().equals("@image:pom.xml")));
            assertTrue(complete(session,"@chrome","@chrome").stream().anyMatch(item -> item.value().equals("@chrome-devtools:file:///a")));
        }
    }
    @Test void slashLineImagesUseTheCapturedRootOnceAcrossAllRegisteredFamilies() throws Exception {
        Files.writeString(root.resolve("only-in-captured-root.txt"),"fixture");
        try (var session=session(List.of("/model","/config","/mcp","/skill","/task","/browser","/snapshot"))) {
            var items=complete(session,"/skill show @image:only-in-captured","@image:only-in-captured");
            assertEquals(List.of("@image:only-in-captured-root.txt"),items.stream().map(AgentSession.CompletionItem::value).toList());
        }
    }
    @Test void theSkillsCapabilityImageCompletionDoesNotFallBackToTheHostWorkingDirectory() throws Exception {
        Files.writeString(root.resolve("only-in-captured-root.txt"),"fixture");
        var config=ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root));
        try (var session=AgentSession.builder().provider(FakeProvider.scripted(List.of(FakeResponse.text("unused"))))
                .model(new Model("fake","fake","fake",128000,8192,Set.of())).config(config)
                .capabilities(List.of(new com.cafecli.harness.skills.SkillsCapability(config.skills()))).build()) {
            var items=complete(session,"/skill show @image:only-in-captured","@image:only-in-captured");
            assertEquals(List.of("@image:only-in-captured-root.txt"),items.stream().map(AgentSession.CompletionItem::value).toList());
        }
    }

    private AgentSession session(List<String> names) {
        List<McpResourceDescriptor> resources=List.of(new McpResourceDescriptor("chrome-devtools","file:///a","a","","","text/plain",null));
        List<Skill> skills=List.of(new Skill("web-access","浏览器和联网策略","1.0.0",null,List.of(),Skill.Source.USER,"body",null,null));
        Capability capability=new Capability() {
            @Override public void attachSession(SessionRegistrar registry) {
                for (String name:names) registry.command(new SessionCommand(name,name,(context,args) -> "")
                        .withCompleter(new CommandCompleter(() -> resources,() -> skills,() -> CommandHints.forCommand(name,name),root)::suggest));
                registry.completion(query -> new CommandCompleter(() -> resources,List::of,List::of,root).suggest(query)
                        .stream().filter(item -> item.value().contains(":")).toList());
            }
        };
        return AgentSession.builder().provider(FakeProvider.scripted(List.of(FakeResponse.text("unused"))))
                .model(new Model("fake","fake","fake",128000,8192,Set.of()))
                .config(ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root)))
                .capabilities(List.of(capability)).build();
    }
    private static List<AgentSession.CompletionItem> complete(AgentSession session,String line,String word) {
        return session.complete(new AgentSession.CompletionQuery(line,word,line.length()));
    }
}
