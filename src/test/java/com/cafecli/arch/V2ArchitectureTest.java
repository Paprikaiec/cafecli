package com.cafecli.arch;

import com.cafecli.app.SystemEntry;
import com.cafecli.core.Agent;
import com.cafecli.core.AgentCallbacks;
import com.cafecli.core.IterationDecision;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.regex.Pattern;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The v2 spine's structure (ADR 0020 decision 1; PRD "Layers and dependency direction"), checked over production
 * classes only. Each rule is one {@code @ArchTest} field; a rule over a layer that does not exist yet passes instead
 * of failing for matching nothing (see {@link #mayOnlyDependOnCafecliClassesIn}).
 *
 * <p>Besides the four layers there is {@code app}, the composition root: it may depend on all of them and on leaves,
 * nothing in the four layers may depend on it, and it is held to the same v1-spine and no-hidden-inputs rules, because
 * it survives switch-over.
 *
 * <p>The {@code ui} rules are written ahead of the layer (tickets 23 / 24), as concretely as the code allows today:
 * {@code ui} may use {@code harness}, {@code core}, {@code ai} and render among Cafecli classes (so not {@code app},
 * not the v1 spine, not other leaves), and none of {@code core}'s engine types. Once {@code ui} exists, tighten
 * {@code harness} to the session facade and {@code core} / {@code ai} to the event and message data types.
 */
@AnalyzeClasses(
        packages = {"com.cafecli.ai", "com.cafecli.core", "com.cafecli.harness", "com.cafecli.ui", "com.cafecli.app"},
        importOptions = ImportOption.DoNotIncludeTests.class)
class V2ArchitectureTest {

    private static final String CAFECLI = "com.cafecli..";

    private static final String AI = "com.cafecli.ai..";
    private static final String CORE = "com.cafecli.core..";
    private static final String HARNESS = "com.cafecli.harness..";
    private static final String UI = "com.cafecli.ui..";
    private static final String RENDER = "com.cafecli.render..";
    private static final String[] V2 = {AI, CORE, HARNESS, UI};
    /** The composition root. */
    private static final String APP = "com.cafecli.app..";
    private static final String[] V2_AND_APP = {AI, CORE, HARNESS, UI, APP};

    /** The v1 spine: deleted at switch-over, so v2 must not touch it. Leaves are not on this list. */
    private static final String[] V1_SPINE_PACKAGES = {
            "com.cafecli.agent..", "com.cafecli.llm..", "com.cafecli.cli..", "com.cafecli.hitl..", "com.cafecli.runtime.."};
    /** v1 spine classes that live in leaf packages. Named, not imported, so the rule outlives their deletion. */
    private static final String[] V1_SPINE_CLASSES = {
            "com.cafecli.memory.MemoryManager",
            "com.cafecli.memory.ContextCompressor",
            "com.cafecli.memory.ConversationHistoryCompactor",
            "com.cafecli.memory.ConversationMemory",
            "com.cafecli.memory.TokenBudget",
            "com.cafecli.tool.ToolRegistry",
            "com.cafecli.tool.ToolBatchExecutor"};

    /**
     * The v2 system entry: the one class in v2 that may read system properties and the environment, once, to build the
     * config loader's sources from the process. (The loader itself, {@code harness.config.ConfigLoader}, reads only the
     * sources it is handed.) Nothing else goes here.
     */
    private static final Class<?>[] V2_SYSTEM_ENTRY = {SystemEntry.class};

    /**
     * {@code core}'s engine — the agent, its callbacks and their decisions, with their nested types — which {@code ui}
     * never reaches into (PRD: "{@code ui} never reaches into {@code Agent} or {@code AgentLoop} internals";
     * {@code AgentLoop} and the tool batch are package-private, so the compiler already keeps them out). Class
     * literals, so a rename breaks this test's compilation instead of silently emptying the rule.
     */
    private static final Class<?>[] CORE_ENGINE_CLASSES = {Agent.class, AgentCallbacks.class, IterationDecision.class};

    @ArchTest
    static final ArchRule ai_depends_on_nothing_else_in_cafecli = mayOnlyDependOnCafecliClassesIn(AI, AI);

    @ArchTest
    static final ArchRule core_depends_only_on_core_and_ai = mayOnlyDependOnCafecliClassesIn(CORE, CORE, AI);

    @ArchTest
    static final ArchRule ui_depends_only_on_ui_harness_core_ai_and_render =
            mayOnlyDependOnCafecliClassesIn(UI, UI, HARNESS, CORE, AI, RENDER);

    @ArchTest
    static final ArchRule ui_does_not_reach_into_the_core_engine = noClasses()
            .that().resideInAPackage(UI)
            .should().dependOnClassesThat(belongToAnyOf(CORE_ENGINE_CLASSES).as("are core's engine"))
            .because("PRD \"ui layer\": ui talks to the harness session facade and reads core's event and message "
                    + "data; it never reaches into Agent or AgentLoop")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule harness_depends_on_neither_ui_nor_app = noClasses()
            .that().resideInAPackage(HARNESS)
            .should().dependOnClassesThat().resideInAnyPackage(UI, APP)
            .because("PRD \"Layers and dependency direction\": harness depends on core, ai and leaves; ui sits above "
                    + "it, and app wires them all");

    @ArchTest
    static final ArchRule nothing_in_the_layers_depends_on_app = noClasses()
            .that().resideInAnyPackage(V2)
            .should().dependOnClassesThat().resideInAPackage(APP)
            .because("app is the composition root (ADR 0020 decision 7): it wires the layers, so no layer may reach "
                    + "back into it");

    @ArchTest
    static final ArchRule v2_does_not_use_the_v1_spine = noClasses()
            .that().resideInAnyPackage(V2_AND_APP)
            .should().dependOnClassesThat(v1Spine())
            .because("PRD \"Layers and dependency direction\": no v2 package imports the v1 spine — v2 reuses leaves, "
                    + "through adapters where they are v1-shaped — so switch-over can delete v1 in one commit");

    @ArchTest
    static final ArchRule v2_reads_no_properties_or_environment_outside_the_system_entry = noClasses()
            .that().resideInAnyPackage(V2_AND_APP)
            .and().doNotBelongToAnyOf(V2_SYSTEM_ENTRY)
            .should().accessTargetWhere(readsPropertiesOrEnvironment())
            .because("v2 has no hidden inputs: the system entry reads the process once, the config loader turns that "
                    + "into configuration, and everything else is passed down");

    /**
     * Classes in {@code layer} depend on no Cafecli class outside {@code allowed} (JDK and third-party classes are not
     * this rule's business). Passes when {@code layer} has no classes yet, so a rule can be written before its layer.
     */
    private static ArchRule mayOnlyDependOnCafecliClassesIn(String layer, String... allowed) {
        return noClasses()
                .that().resideInAPackage(layer)
                .should().dependOnClassesThat(resideInAPackage(CAFECLI).and(resideOutsideOfPackages(allowed)))
                .allowEmptyShould(true);
    }

    /** A class in a v1 spine package, or one of the named v1 spine classes or a class nested in it. */
    private static DescribedPredicate<JavaClass> v1Spine() {
        return resideInAnyPackage(V1_SPINE_PACKAGES).or(namedOrNestedIn(V1_SPINE_CLASSES))
                .as("are part of the v1 spine");
    }

    /** One of the named classes, or a class nested in one. */
    private static DescribedPredicate<JavaClass> namedOrNestedIn(String... names) {
        DescribedPredicate<JavaClass> named = DescribedPredicate.alwaysFalse();
        for (String name : names) {
            named = named.or(nameMatching(Pattern.quote(name) + "(\\$.*)?"));
        }
        return named;
    }

    /** Calls to, or method references of, the JDK's property and environment readers. */
    private static DescribedPredicate<JavaAccess<?>> readsPropertiesOrEnvironment() {
        return accessTo(System.class, "getProperty|getProperties|getenv")
                .or(accessTo(Integer.class, "getInteger"))
                .or(accessTo(Long.class, "getLong"))
                .or(accessTo(Boolean.class, "getBoolean"));
    }

    private static DescribedPredicate<JavaAccess<?>> accessTo(Class<?> owner, String methodNames) {
        return JavaAccess.Predicates.target(AccessTarget.Predicates.declaredIn(owner).and(nameMatching(methodNames)));
    }
}
