package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every {@link ChatSessionStore} must do, whatever it stores chats in.
 *
 * <p>One contract, one subclass per implementation, for the reason the session
 * contract gives: a durable store that differed would misbehave only for the
 * users who had opted into durability.
 *
 * <p>Ordering gets the most attention here because this store backs the sidebar,
 * where wrong order is visible on every page load and traceable to nothing.
 * Fixtures set {@code updatedAt} explicitly through a snapshot rather than by
 * saving in sequence and hoping the clock moves: the durable store orders on a
 * millisecond column, so two chats saved back to back can genuinely tie, and a
 * test that relied on them not tying would fail once a month on a fast machine.
 */
public abstract class ChatSessionStoreContractTest {

    /**
     * @return a fresh, empty store of the implementation under test
     */
    protected abstract ChatSessionStore createStore();

    @Test
    void aSavedChatComesBackWithItsTurns() {
        ChatSessionStore store = createStore();
        ChatSession chat = new ChatSession("c1", "multi-cloud", DepthMode.RIGOROUS, "so far");
        chat.addTurn(ChatTurn.running("t1", "first", "session-1").completed("answer one"));
        chat.addTurn(ChatTurn.running("t2", "second", "session-2"));

        store.save(chat);

        ChatSession found = store.findById("c1").orElseThrow();
        assertEquals(List.of("t1", "t2"), found.turns().stream().map(ChatTurn::id).toList());
        assertEquals("answer one", found.turn("t1").orElseThrow().assistantAnswer());
        assertEquals("so far", found.summary());
        assertEquals(DepthMode.RIGOROUS, found.depthMode());
        assertEquals(chat.createdAt(), found.createdAt());
    }

    @Test
    void anUnknownIdIsEmptyRatherThanAnError() {
        assertTrue(createStore().findById("never-existed").isEmpty());
    }

    @Test
    void savingAgainReplacesRatherThanDuplicates() {
        ChatSessionStore store = createStore();
        ChatSession chat = new ChatSession("c2", "mock", DepthMode.QUICK, "");
        chat.addTurn(ChatTurn.running("t1", "question", "session-1"));
        store.save(chat);

        chat.replaceTurn(chat.turn("t1").orElseThrow().completed("the answer"));
        store.save(chat);

        assertEquals(1, store.findAll().size());
        assertEquals(ChatTurnStatus.COMPLETED,
                     store.findById("c2").orElseThrow().turn("t1").orElseThrow().status());
    }

    @Test
    void findAllReturnsMostRecentlyUpdatedFirst() {
        ChatSessionStore store = createStore();
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(600);
        store.save(chatUpdatedAt("older", base));
        store.save(chatUpdatedAt("newest", base.plusSeconds(20)));
        store.save(chatUpdatedAt("middle", base.plusSeconds(10)));

        assertEquals(List.of("newest", "middle", "older"),
                     store.findAll().stream().map(ChatSession::id).toList());
    }

    @Test
    void chatsTiedOnUpdatedAtStillComeBackInAStableOrder() {
        // The durable store orders on a millisecond column, so ties are real.
        // An unstable order here means the sidebar reshuffles between reads.
        ChatSessionStore store = createStore();
        Instant same = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(600);
        store.save(chatUpdatedAt("aaa", same));
        store.save(chatUpdatedAt("ccc", same));
        store.save(chatUpdatedAt("bbb", same));

        assertEquals(List.of("ccc", "bbb", "aaa"),
                     store.findAll().stream().map(ChatSession::id).toList());
        assertEquals(store.findAll().stream().map(ChatSession::id).toList(),
                     store.findAll().stream().map(ChatSession::id).toList());
    }

    @Test
    void findAllOnAnEmptyStoreIsEmptyRatherThanNull() {
        assertTrue(createStore().findAll().isEmpty());
    }

    @Test
    void deleteRemovesTheChatAndSaysSo() {
        ChatSessionStore store = createStore();
        store.save(new ChatSession("c3", "mock", DepthMode.QUICK, ""));

        assertTrue(store.delete("c3"));
        assertTrue(store.findById("c3").isEmpty());
        assertTrue(store.findAll().isEmpty());
    }

    @Test
    void deletingAChatThatIsNotThereReportsFalse() {
        // The API turns this into a 404 rather than a cheerful 204 for a chat
        // that never existed.
        ChatSessionStore store = createStore();
        store.save(new ChatSession("c4", "mock", DepthMode.QUICK, ""));

        assertFalse(store.delete("never-existed"));
        // Control: delete does return true when it removes something, so the
        // false above is the missing row rather than a method stuck on false.
        assertTrue(store.delete("c4"));
    }

    @Test
    void deleteRemovesOnlyTheChatNamed() {
        ChatSessionStore store = createStore();
        store.save(new ChatSession("keep", "mock", DepthMode.QUICK, ""));
        store.save(new ChatSession("drop", "mock", DepthMode.QUICK, ""));

        store.delete("drop");

        assertEquals(List.of("keep"), store.findAll().stream().map(ChatSession::id).toList());
    }

    @Test
    void storesAreIndependentOfEachOther() {
        ChatSessionStore first = createStore();
        first.save(new ChatSession("c5", "mock", DepthMode.QUICK, ""));

        assertTrue(createStore().findById("c5").isEmpty(),
                   "each createStore() call must hand back an empty store");
        assertTrue(first.findById("c5").isPresent());
    }

    /**
     * A chat with an explicitly chosen {@code updatedAt}.
     *
     * <p>Built through the snapshot because that is the only way to set the
     * timestamp exactly; going through {@code addTurn} would stamp
     * {@code Instant.now()} and leave ordering to the clock.
     *
     * @param id        the chat id
     * @param updatedAt the timestamp the store should order on
     * @return the chat
     */
    private ChatSession chatUpdatedAt(String id, Instant updatedAt) {
        return ChatSession.fromSnapshot(new ChatSessionSnapshot(
                id, "mock", DepthMode.QUICK, "", List.of(), updatedAt.minusSeconds(60), updatedAt));
    }
}
