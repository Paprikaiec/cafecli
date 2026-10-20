package com.cafecli.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.regex.Pattern;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The v2 spine's structure (ADR 0020 decision 1; PRD "Layers and dependency direction"), checked over production
 * classes only. Each rule is one {@code @ArchTest} field; a rule over a layer that does not exist yet passes instead
 * of failing for matching nothing (see {@link #mayOnlyDependOnCafecliClassesIn}).
 */
@AnalyzeClasses(
        packages = {"com.cafecli.ai", "com.cafecli.core", "com.cafecli.harness", "com.cafecli.ui"},
        importOptions = ImportOption.DoNotIncludeTests.class)
class V2ArchitectureTest {

    private static final String CAFECLI = "com.cafecli..";

    private static final String AI = "com.cafecli.ai..";
    private static final String CORE = "com.cafecli.core..";
    private static final String HARNESS = "com.cafecli.harness..";
    private static final String UI = "com.cafecli.ui..";
    private static final String[] V2 = {AI, CORE, HARNESS, UI};

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
     * The v2 config loader, once it exists — the one class in v2 that may read system properties and the environment.
     * Nothing else goes here.
     */
    private static final Class<?>[] V2_CONFIG_LOADER = {};

    @ArchTest
    static final ArchRule ai_depends_on_nothing_else_in_cafecli = mayOnlyDependOnCafecliClassesIn(AI, AI);

    @ArchTest
    static final ArchRule core_depends_only_on_core_and_ai = mayOnlyDependOnCafecliClassesIn(CORE, CORE, AI);

    @ArchTest
    static final ArchRule v2_does_not_use_the_v1_spine = noClasses()
            .that().resideInAnyPackage(V2)
            .should().dependOnClassesThat(v1Spine())
            .because("PRD \"Layers and dependency direction\": no v2 package imports the v1 spine — v2 reuses leaves, "
                    + "through adapters where they are v1-shaped — so switch-over can delete v1 in one commit");

    @ArchTest
    static final ArchRule v2_reads_no_properties_or_environment_outside_the_config_loader = noClasses()
            .that().resideInAnyPackage(V2)
            .and().doNotBelongToAnyOf(V2_CONFIG_LOADER)
            .should().accessTargetWhere(readsPropertiesOrEnvironment())
            .because("v2 has no hidden inputs: configuration is loaded once, by the config loader, and passed down");

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
        DescribedPredicate<JavaClass> spine = resideInAnyPackage(V1_SPINE_PACKAGES);
        for (String name : V1_SPINE_CLASSES) {
            spine = spine.or(nameMatching(Pattern.quote(name) + "(\\$.*)?"));
        }
        return spine.as("are part of the v1 spine");
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
