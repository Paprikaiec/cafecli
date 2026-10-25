package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.*;
import com.cafecli.harness.config.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** Literal acceptance cases from the legacy parser contract, exercised through the composed public registry. */
class CommandGrammarSessionTest {
    @TempDir Path root;
    private static final List<String> NAMES=List.of("/exit","/quit","/cancel","/clear","/transcript","/history","/model",
            "/plan","/hitl","/memory","/mem","/save","/index","/search","/graph","/context","/ctx","/policy","/config",
            "/audit","/snapshot","/restore","/browser","/task","/skill","/hooks","/mcp");

    @ParameterizedTest @MethodSource("accepted")
    void acceptedLegacySyntaxDispatchesToItsRegisteredOwner(String input) {
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        try (var session=session(provider)) {
            assertTrue(session.dispatch(input).isPresent(),input);
            assertTrue(provider.requests().isEmpty()); assertTrue(session.log().isEmpty());
        }
    }
    static Stream<String> accepted() {
        return Stream.of("/exit","/quit","exit","quit","/cancel","cancel","/clear","clear","/transcript","/history clear",
                "/model","/model step","/plan","/plan any text","/hitl","/hitl on","/hitl off",
                "/memory","/mem","/memory clear","/mem clear","/memory list","/mem list",
                "/memory delete id extra","/mem delete id","/memory search two words","/mem search q",
                "/save","/save --global fact","/index","/index some path","/search","/search query","/graph","/graph ClassName",
                "/context","/ctx","/policy","/config","/config provider step --model x","/audit","/audit nope extra",
                "/snapshot","/snapshot nonsense extra","/restore","/restore nope extra","/browser","/browser connect 9222 extra",
                "/task","/task cancel\tid","/skill","/skill list","/skill reload","/skill show a b","/skill on a b","/skill off a b",
                "/hooks","/hooks list","/mcp","/mcp restart server more","/mcp logs server","/mcp disable server",
                "/mcp enable server","/mcp resources server","/mcp prompts server",
                " \t/SkIlL ShOw \tname\t ","/MoDeL  \tstep extra"," /MEM search  two words ","/mcp restart  server","/plan \tanything",
                "/exit\t","/context\t","/model\t");
    }
    @ParameterizedTest @MethodSource("rejected")
    void malformedLegacySyntaxDoesNotExecuteARegisteredCommandOrReachTheModel(String input) {
        var provider=FakeProvider.scripted(List.of(FakeResponse.text("unused")));
        try (var session=session(provider)) {
            assertTrue(session.dispatch(input).isEmpty(),input);
            assertTrue(provider.requests().isEmpty()); assertTrue(session.log().isEmpty());
        }
    }
    static Stream<String> rejected() {
        return Stream.of("/unknown","/exit extra","/quit extra","exit extra","clear extra","/clear extra","/cancel extra",
                "/transcript extra","/history","/history  clear","/history\tclear","/history clear extra",
                "/model\tstep","/plan\ttext","/hitl  on","/hitl\ton","/hitl on extra","/hitl invalid",
                "/memory unknown","/memory clear extra","/mem list extra","/memory  list","/mem\tlist",
                "/memory delete","/memory search","/mem delete","/mem search","/save\tfact","/index\tpath",
                "/search\tquery","/graph\tClassName","/context extra","/ctx extra","/policy extra","/config\tprovider step",
                "/audit\t10","/snapshot\tstatus","/restore\t1","/browser\tstatus","/task\tlist","/skill\tlist",
                "/skill list extra","/skill reload extra","/skill show","/skill on","/skill off","/skill show\tname",
                "/skill  show name","/hooks extra","/hooks list extra","/hooks  list","/mcp list","/mcp restart",
                "/mcp resources","/mcp prompts","/mcp logs","/mcp enable","/mcp disable","/mcp  restart server",
                "/mcp\trestart server","/mcp restart\tserver");
    }
    private AgentSession session(FakeProvider provider) {
        Capability commands=new Capability() {
            @Override public void attachSession(SessionRegistrar registrar) {
                for (String name:NAMES) registrar.command(SessionCommand.control(name,name,(context,args) -> name+":"+args));
            }
        };
        return AgentSession.builder().provider(provider).model(new Model("fake","fake","fake",128000,8192,Set.of()))
                .config(ConfigLoader.load(new ConfigSources(Map.of(),Map.of(),root.resolve("home"),root))).capabilities(List.of(commands)).build();
    }
}
