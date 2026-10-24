package com.cafecli.harness;

import com.cafecli.ai.Model;
import com.cafecli.ai.fake.FakeProvider;
import com.cafecli.ai.fake.FakeResponse;
import com.cafecli.ai.message.UserMessage;
import com.cafecli.core.AgentEvent;
import com.cafecli.harness.config.ConfigLoader;
import com.cafecli.harness.config.ConfigSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveSessionTest {
    static final Model MODEL = new Model("fake", "fake", "fake", 128_000, 8192, Set.of());
    @TempDir Path root;

    @Test void submissionRunsOnOneOwnerAndReportsBothActivityTransitions() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeProvider provider = FakeProvider.responding(request -> {
            entered.countDown(); await(release); return FakeResponse.text("reply");
        });
        List<Boolean> activity = new CopyOnWriteArrayList<>();
        List<Thread> owners = new CopyOnWriteArrayList<>();
        try (var session = session(provider)) {
            session.subscribeActivity(activity::add);
            session.subscribe(event -> { if (event instanceof AgentEvent.RunStart) owners.add(Thread.currentThread()); });
            try {
                assertInstanceOf(SubmitResult.Queued.class, session.submit("hello"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(List.of(true), activity);
            } finally { release.countDown(); }
            session.awaitIdle();
            assertEquals(List.of(true, false), activity);
            assertEquals(1, owners.size());
            assertNotEquals(Thread.currentThread(), owners.getFirst());
            assertEquals(List.of(UserMessage.text("hello")), provider.requests().getFirst().context().messages());
            session.submit("again"); session.awaitIdle();
            assertEquals(owners.getFirst(), owners.getLast());
        }
    }

    @Test void twoCompletionsMissingTheLastBoundaryProduceOneAutonomousRun() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var inbox = new java.util.concurrent.atomic.AtomicReference<BoundaryInbox>();
        Capability notifications = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(com.cafecli.harness.tasks.TaskNotification.KIND);
                inbox.set(scope.inbox());
            }
        };
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        FakeProvider provider = FakeProvider.responding(request -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); await(release); }
            return FakeResponse.text("reply");
        });
        try (var session = session(provider, notifications)) {
            try {
                session.submit("start");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                inbox.get().post(new com.cafecli.harness.tasks.TaskNotification("task_1", "ONE"));
                inbox.get().post(new com.cafecli.harness.tasks.TaskNotification("task_2", "TWO"));
            } finally { release.countDown(); }
            session.awaitIdle();
            assertEquals(2, provider.requests().size());
            assertEquals(UserMessage.text("<task-notification task_id=\"task_1\">\nONE\n</task-notification>\n\n"
                    + "<task-notification task_id=\"task_2\">\nTWO\n</task-notification>"),
                    provider.requests().get(1).context().messages().getLast());
            assertEquals(1, session.log().stream().filter(com.cafecli.core.log.UserEntry.class::isInstance).count());
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }

    @Test void busyInputsArePreparedOnceAndBlockedInputsNeverReachTheModel() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<String> prepared = new CopyOnWriteArrayList<>();
        Capability preparation = new Capability() {
            @Override public void attachSession(SessionRegistrar registrar) {
                registrar.onSubmit((input, token) -> {
                    prepared.add(input);
                    return input.equals("blocked") ? Submission.block("no") : Submission.proceed(input + " prepared");
                });
            }
        };
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        FakeProvider provider = FakeProvider.responding(request -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); await(release); }
            return FakeResponse.text("reply");
        });
        try (var session = session(provider, preparation)) {
            try {
                session.submit("ordinary");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertInstanceOf(SubmitResult.Blocked.class, session.submit("blocked"));
                assertInstanceOf(SubmitResult.SteeringAccepted.class, session.submit("busy"));
            } finally { release.countDown(); }
            session.awaitIdle();
            assertEquals(List.of("ordinary", "blocked", "busy"), prepared);
            assertEquals(UserMessage.text("ordinary prepared"), provider.requests().getFirst().context().messages().getFirst());
            assertEquals(UserMessage.text("busy prepared"), provider.requests().getLast().context().messages().getLast());
            assertTrue(provider.requests().stream().noneMatch(request -> request.context().messages().toString().contains("blocked")));
        }
    }

    @Test void ordinaryCommandsUseTheSerialOwnerAndControlsRemainIndependentWhileBusy() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        List<Thread> owners = new CopyOnWriteArrayList<>();
        Capability commands = new Capability() {
            @Override public void attachSession(SessionRegistrar registrar) {
                registrar.command(new SessionCommand("/ordinary", "ordinary", (session, input) -> {
                    owners.add(Thread.currentThread()); session.clear(); return "done";
                }));
                registrar.command(SessionCommand.control("/control", "control", (session, input) -> "inspected"));
            }
        };
        FakeProvider provider = FakeProvider.responding(request -> {
            entered.countDown(); await(release); return FakeResponse.text("reply");
        });
        try (var session = session(provider, commands)) {
            assertEquals("done", session.dispatch("/ordinary").orElseThrow());
            session.subscribe(event -> { if (event instanceof AgentEvent.RunStart) owners.add(Thread.currentThread()); });
            try {
                session.submit("start"); assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals("inspected", session.dispatch("/control").orElseThrow());
                assertThrows(IllegalStateException.class, () -> session.dispatch("/ordinary"));
                assertThrows(IllegalStateException.class, session::clear);
            } finally { release.countDown(); }
            session.awaitIdle();
            assertEquals(owners.getFirst(), owners.getLast());
        }
    }

    @Test void anAutonomousRunStripsHistoricalImagesWithoutInventingAUserOrReadingTheFileAgain() throws Exception {
        java.nio.file.Files.write(root.resolve("shot.png"), new byte[]{1, 2, 3});
        var inbox = new java.util.concurrent.atomic.AtomicReference<BoundaryInbox>();
        Capability notifications = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(com.cafecli.harness.tasks.TaskNotification.KIND); inbox.set(scope.inbox());
            }
        };
        FakeProvider provider = FakeProvider.responding(request -> FakeResponse.text("reply"));
        try (var session = session(provider, new com.cafecli.harness.images.ImagesCapability(root), notifications)) {
            session.submit("Analyze @image:shot.png"); session.awaitIdle();
            java.nio.file.Files.delete(root.resolve("shot.png"));
            inbox.get().post(new com.cafecli.harness.tasks.TaskNotification("task_1", "finished"));
            session.awaitIdle();
            assertEquals(2, provider.requests().size());
            var historical = assertInstanceOf(UserMessage.class, provider.requests().get(1).context().messages().getFirst());
            assertTrue(historical.content().stream().noneMatch(com.cafecli.ai.message.ImageBlock.class::isInstance));
            assertTrue(historical.content().toString().contains("历史图片附件已省略 1 张"));
            assertEquals(1, session.log().stream().filter(com.cafecli.core.log.UserEntry.class::isInstance).count());
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }

    @Test void aLateCompletionAfterCloseCannotStrandDeliveryOrRestartTheLoop() throws Exception {
        var inbox = new java.util.concurrent.atomic.AtomicReference<BoundaryInbox>();
        Capability notifications = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(com.cafecli.harness.tasks.TaskNotification.KIND); inbox.set(scope.inbox());
            }
        };
        FakeProvider provider = FakeProvider.responding(request -> FakeResponse.text("reply"));
        var session = session(provider, notifications);
        session.close();
        assertDoesNotThrow(() -> inbox.get().post(new com.cafecli.harness.tasks.TaskNotification("task_1", "late")));
        session.awaitIdle();
        assertTrue(provider.requests().isEmpty());
    }

    @Test void callbackFailurePreservesPreparedFollowUpsAndFailingActivityObserversCannotStrandAdmission() throws Exception {
        CountDownLatch end = new CountDownLatch(1), release = new CountDownLatch(1);
        List<SessionCompletion> completions = new CopyOnWriteArrayList<>();
        var ends = new java.util.concurrent.atomic.AtomicInteger();
        var preparations = new java.util.concurrent.atomic.AtomicInteger();
        Capability preparation = new Capability() {
            @Override public void attachSession(SessionRegistrar registrar) {
                registrar.onSubmit((input, token) -> { preparations.incrementAndGet(); return Submission.proceed(input); });
            }
        };
        var provider = FakeProvider.responding(request -> FakeResponse.text("done"));
        try (var session = session(provider, preparation)) {
            session.subscribeCompletion(completions::add);
            session.subscribeActivity(busy -> { throw new IllegalStateException("activity failure"); });
            session.subscribe(event -> {
                if (event instanceof AgentEvent.MessageEnd && ends.getAndIncrement() == 0) {
                    end.countDown(); await(release); throw new IllegalStateException("message failure");
                }
            });
            try {
                session.submit("first"); assertTrue(end.await(5, TimeUnit.SECONDS));
                session.submit("second"); session.submit("third");
            } finally { release.countDown(); }
            session.awaitIdle();
            assertEquals(2, completions.size());
            assertEquals("message failure", assertInstanceOf(SessionCompletion.Failed.class, completions.getFirst()).failure().getMessage());
            assertInstanceOf(SessionCompletion.Completed.class, completions.getLast());
            assertEquals(3, preparations.get());
            assertEquals(UserMessage.text("second\nthird"), provider.requests().getLast().context().messages().getLast());
            assertTrue(session.takeFollowUpInputs().isEmpty());
            session.clear();
        }
    }

    @Test void autonomousInputConsumesThePendingSkillPrefixOnceAndCachesItsImagesLosslessly() throws Exception {
        Path skill = root.resolve(".cafecli/skills/apricot/SKILL.md");
        java.nio.file.Files.createDirectories(skill.getParent());
        java.nio.file.Files.writeString(skill, "---\nname: apricot\ndescription: Test apricot\n---\nUse apricot @image:shot.png\n");
        java.nio.file.Files.write(root.resolve("shot.png"), new byte[]{1, 2, 3});
        var config = ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root));
        var inbox = new java.util.concurrent.atomic.AtomicReference<BoundaryInbox>();
        Capability notifications = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(com.cafecli.harness.tasks.TaskNotification.KIND); inbox.set(scope.inbox());
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.builder().toolCall("load", "load_skill", "{\"name\":\"apricot\"}").build(),
                FakeResponse.text("loaded"), FakeResponse.text("autonomous"), FakeResponse.text("later")));
        try (var session = session(provider, new com.cafecli.harness.skills.SkillsCapability(config.skills()),
                new com.cafecli.harness.images.ImagesCapability(root), notifications)) {
            session.submit("load it"); session.awaitIdle();
            assertFalse(provider.requests().get(1).context().messages().toString().contains("Use apricot"));
            inbox.get().post(new com.cafecli.harness.tasks.TaskNotification("task_1", "done")); session.awaitIdle();
            var message = assertInstanceOf(UserMessage.class, provider.requests().get(2).context().messages().getLast());
            assertTrue(message.content().toString().contains("## 已加载 Skill：apricot"));
            assertTrue(message.content().toString().contains("<task-notification task_id=\"task_1\">"));
            assertEquals(new com.cafecli.ai.message.ImageBlock("AQID", "image/png"), message.content().getLast());
            var entry = session.log().stream().filter(com.cafecli.harness.context.RunInputEntry.class::isInstance)
                    .map(com.cafecli.harness.context.RunInputEntry.class::cast).findFirst().orElseThrow();
            assertEquals(1, entry.metadata().stream().filter(com.cafecli.harness.skills.SkillBody.class::isInstance).count());
            java.nio.file.Files.delete(root.resolve("shot.png"));
            session.submit("later"); session.awaitIdle();
            assertEquals(UserMessage.text("later"), provider.requests().get(3).context().messages().getLast());
            var historical = assertInstanceOf(UserMessage.class, provider.requests().get(3).context().messages().get(4));
            assertTrue(historical.content().stream().noneMatch(com.cafecli.ai.message.ImageBlock.class::isInstance));
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
            assertEquals(2, session.log().stream().filter(com.cafecli.core.log.UserEntry.class::isInstance).count());
        }
    }

    @Test void groupedNotificationsAndRealInputKeepSeparateSourcesAndOnePreparedUserPresentation() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        java.nio.file.Files.write(root.resolve("shot.png"), new byte[]{1, 2, 3});
        var inbox = new java.util.concurrent.atomic.AtomicReference<BoundaryInbox>();
        Capability notifications = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(com.cafecli.harness.tasks.TaskNotification.KIND); inbox.set(scope.inbox());
            }
        };
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var provider = FakeProvider.responding(request -> {
            if (calls.getAndIncrement() == 0) { entered.countDown(); await(release); }
            return FakeResponse.text("done");
        });
        try (var session = session(provider, new com.cafecli.harness.images.ImagesCapability(root), notifications)) {
            try {
                session.submit("first"); assertTrue(entered.await(5, TimeUnit.SECONDS));
                session.submit("Analyze @image:shot.png");
                inbox.get().post(new com.cafecli.harness.tasks.TaskNotification("task_1", "done"));
            } finally { release.countDown(); }
            session.awaitIdle();
            var source = session.log().stream().filter(com.cafecli.core.log.UserEntry.class::isInstance)
                    .map(com.cafecli.core.log.UserEntry.class::cast).toList().getLast();
            assertEquals(new com.cafecli.ai.message.TextBlock("Analyze @image:shot.png"), source.message().content().getFirst());
            assertFalse(source.message().content().toString().contains("task-notification"));
            var message = assertInstanceOf(UserMessage.class, provider.requests().get(1).context().messages().getLast());
            assertTrue(message.content().toString().contains("<task-notification task_id=\"task_1\">"));
            assertEquals(new com.cafecli.ai.message.ImageBlock("AQID", "image/png"), message.content().getLast());
            assertEquals(3, provider.requests().get(1).context().messages().size());
            var metadata = session.log().stream().filter(com.cafecli.harness.context.RunInputEntry.class::isInstance)
                    .map(com.cafecli.harness.context.RunInputEntry.class::cast).findFirst().orElseThrow();
            assertEquals(List.of(new com.cafecli.harness.tasks.TaskNotification("task_1", "done")), metadata.sources());
            assertEquals(session.log(), session.messageKinds().json().read(session.messageKinds().json().write(session.log())));
        }
    }

    @Test void aBusyCompletionEntersTheNextEligibleIterationWithoutAnExtraRun() throws Exception {
        var boundary = new CountDownLatch(1); var release = new CountDownLatch(1);
        var inbox = new java.util.concurrent.atomic.AtomicReference<BoundaryInbox>();
        var iterations = new java.util.concurrent.atomic.AtomicInteger();
        Capability notifications = new Capability() {
            @Override public void attachAgent(AgentScope scope) {
                scope.messageKind(com.cafecli.harness.tasks.TaskNotification.KIND); inbox.set(scope.inbox());
                scope.iterationEnd((message, results) -> {
                    if (iterations.getAndIncrement() == 0) {
                        boundary.countDown(); await(release); return com.cafecli.core.IterationDecision.proceed();
                    }
                    return com.cafecli.core.IterationDecision.end();
                });
            }
        };
        var provider = FakeProvider.scripted(List.of(FakeResponse.text("first"), FakeResponse.text("done")));
        var completions = new CopyOnWriteArrayList<SessionCompletion>();
        try (var session = session(provider, notifications)) {
            session.subscribeCompletion(completions::add);
            try {
                session.submit("start"); assertTrue(boundary.await(5, TimeUnit.SECONDS));
                inbox.get().post(new com.cafecli.harness.tasks.TaskNotification("task_1", "ready"));
            } finally { release.countDown(); }
            session.awaitIdle();
            assertEquals(1, completions.size()); assertEquals(2, provider.requests().size());
            assertEquals(UserMessage.text("<task-notification task_id=\"task_1\">\nready\n</task-notification>"),
                    provider.requests().getLast().context().messages().getLast());
            assertEquals(1, session.log().stream().filter(com.cafecli.harness.tasks.TaskNotification.class::isInstance).count());
        }
    }

    AgentSession session(FakeProvider provider, Capability... capabilities) {
        return AgentSession.builder().provider(provider).model(MODEL).interactive(true)
                .config(ConfigLoader.load(new ConfigSources(Map.of(), Map.of(), root.resolve("home"), root)))
                .capabilities(List.of(capabilities)).build();
    }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
}
