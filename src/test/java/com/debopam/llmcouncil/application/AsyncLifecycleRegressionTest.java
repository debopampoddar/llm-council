package com.debopam.llmcouncil.application;

import com.debopam.llmcouncil.chat.*;
import com.debopam.llmcouncil.domain.CouncilSession;
import com.debopam.llmcouncil.domain.CouncilStatus;
import com.debopam.llmcouncil.domain.DepthMode;
import com.debopam.llmcouncil.orchestration.CouncilContext;
import com.debopam.llmcouncil.support.TestCatalogs;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AsyncLifecycleRegressionTest {

    @Test
    void completionCallbackFailureIsNotDeliveredTwiceAndPermitIsReleased() throws Exception {
        StubCouncilService service = new StubCouncilService();
        CouncilRunExecutor executor = new CouncilRunExecutor(service, TestCatalogs.holder(1, "/tmp"));
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch called = new CountDownLatch(1);
        try {
            assertTrue(executor.submit("first", completion -> {
                callbacks.incrementAndGet();
                called.countDown();
                throw new IllegalStateException("consumer failed");
            }).accepted());
            assertTrue(called.await(2, TimeUnit.SECONDS));

            CouncilRunSubmission second = awaitAccepted(executor, "second");
            assertTrue(second.accepted(), second.message());
            assertEquals(1, callbacks.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void concurrencyLimitRejectsWorkUntilTheActiveRunFinishes() throws Exception {
        BlockingCouncilService service = new BlockingCouncilService();
        CouncilRunExecutor executor = new CouncilRunExecutor(service, TestCatalogs.holder(1, "/tmp"));
        try {
            assertTrue(executor.submit("one", ignored -> {}).accepted());
            assertTrue(service.started.await(2, TimeUnit.SECONDS));
            assertFalse(executor.submit("two", ignored -> {}).accepted());
            service.release.countDown();
            assertTrue(service.finished.await(2, TimeUnit.SECONDS));
            assertTrue(awaitAccepted(executor, "three").accepted());
        } finally {
            service.release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void cancellationRequestedBeforeRegistrationIsAppliedOnRegistration() {
        RunRegistry registry = new RunRegistry();
        CouncilContext context = mock(CouncilContext.class);

        assertFalse(registry.cancel("session"));
        registry.register("session", context);

        verify(context).cancel();
        assertSame(context, registry.find("session").orElseThrow());
        registry.unregister("session");
        assertTrue(registry.find("session").isEmpty());
    }

    @Test
    void terminalCleanupRemovesACancellationThatCanNoLongerBeConsumed() {
        RunRegistry registry = new RunRegistry();
        CouncilContext context = mock(CouncilContext.class);

        registry.cancel("session");
        registry.clearPendingCancellation("session");
        registry.register("session", context);

        verify(context, never()).cancel();
    }

    @Test
    void chatTerminalEventIsPublishedOnlyAfterUpdatedTurnIsStored() throws Exception {
        CouncilService councils = mock(CouncilService.class);
        CapturingExecutor executor = new CapturingExecutor(councils);
        InMemoryChatSessionStore store = new InMemoryChatSessionStore();
        ChatEventBroker events = new ChatEventBroker();
        CouncilSessionCleanup cleanup = mock(CouncilSessionCleanup.class);
        ChatCouncilService chats = new ChatCouncilService(
                store, councils, executor, events, new InMemoryRunResultStore(),
                new ChatTurnAttribution(new InMemoryChatSequenceAllocator()),
                cleanup,
                TestCatalogs.holder(1, "/tmp"));

        ChatSession chat = chats.createChat("mock", DepthMode.QUICK, null);
        chats.ask(chat.id(), "question");
        String sessionId = chat.turns().getFirst().councilSessionId();
        AtomicReference<ChatTurnStatus> statusSeenBySubscriber = new AtomicReference<>();
        events.subscribe(chat.id(), event -> {
            if ("TURN_COMPLETED".equals(event.type())) {
                statusSeenBySubscriber.set(store.findById(chat.id()).orElseThrow()
                        .turns().getFirst().status());
            }
        });

        executor.complete(completed(sessionId));

        assertEquals(ChatTurnStatus.COMPLETED, statusSeenBySubscriber.get());
        assertEquals("answer", store.findById(chat.id()).orElseThrow().turns().getFirst().assistantAnswer());

        chats.deleteChat(chat.id());
        verify(cleanup).delete(sessionId);
        assertTrue(store.findById(chat.id()).isEmpty());
    }

    @Test
    void aChatRefusesOverlappingTurns() {
        CouncilService councils = mock(CouncilService.class);
        CapturingExecutor executor = new CapturingExecutor(councils);
        ChatCouncilService chats = new ChatCouncilService(
                new InMemoryChatSessionStore(), councils, executor, new ChatEventBroker(),
                new InMemoryRunResultStore(), new ChatTurnAttribution(new InMemoryChatSequenceAllocator()),
                mock(CouncilSessionCleanup.class),
                TestCatalogs.holder(2, "/tmp"));
        ChatSession chat = chats.createChat("mock", DepthMode.QUICK, null);

        chats.ask(chat.id(), "first");
        assertThrows(IllegalStateException.class, () -> chats.ask(chat.id(), "second"));
        assertEquals(1, chats.getChat(chat.id()).turns().size());
    }

    private CouncilRunSubmission awaitAccepted(CouncilRunExecutor executor, String id) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        CouncilRunSubmission submission;
        do {
            submission = executor.submit(id, ignored -> {});
            if (submission.accepted()) return submission;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        return submission;
    }

    private CouncilSession completed(String id) {
        Instant now = Instant.now();
        return new CouncilSession(id, "question", null, DepthMode.QUICK, "mock",
                "mock-quick", "quick", CouncilStatus.COMPLETED, now, now, "answer", null);
    }

    private static class StubCouncilService extends CouncilService {
        StubCouncilService() { super(null, null, null, new RunRegistry()); }
        @Override public CouncilContext runCouncil(String sessionId) { return null; }
        @Override public CouncilSession getSession(String sessionId) {
            Instant now = Instant.now();
            return new CouncilSession(sessionId, "q", null, DepthMode.QUICK, "mock",
                    "p", "quick", CouncilStatus.COMPLETED, now, now, "answer", null);
        }
    }

    private static final class BlockingCouncilService extends StubCouncilService {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        @Override public CouncilContext runCouncil(String sessionId) {
            started.countDown();
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { finished.countDown(); }
            return null;
        }
    }

    private static final class CapturingExecutor extends CouncilRunExecutor {
        private Consumer<CouncilRunCompletion> callback;
        CapturingExecutor(CouncilService service) { super(service, TestCatalogs.holder(1, "/tmp")); }
        @Override public CouncilRunSubmission submit(String id, Consumer<CouncilRunCompletion> callback) {
            this.callback = callback;
            return CouncilRunSubmission.accepted(id);
        }
        void complete(CouncilSession session) {
            callback.accept(new CouncilRunCompletion(session.id(), true, session, null, null));
        }
    }
}
