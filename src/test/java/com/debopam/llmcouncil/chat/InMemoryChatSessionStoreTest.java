package com.debopam.llmcouncil.chat;

import com.debopam.llmcouncil.domain.DepthMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryChatSessionStoreTest {

    @Test
    void listsChatsMostRecentlyUpdatedFirst() throws Exception {
        InMemoryChatSessionStore store = new InMemoryChatSessionStore();
        ChatSession older = chat("older");
        store.save(older);

        // ChatSession stamps updatedAt from the wall clock, so the two chats
        // need a distinguishable gap for the ordering assertion to mean anything.
        Thread.sleep(5);
        ChatSession newer = chat("newer");
        store.save(newer);

        List<String> ids = store.findAll().stream().map(ChatSession::id).toList();

        assertEquals(List.of("newer", "older"), ids);
    }

    @Test
    void deleteRemovesOnlyTheNamedChat() {
        InMemoryChatSessionStore store = new InMemoryChatSessionStore();
        store.save(chat("a"));
        store.save(chat("b"));

        assertTrue(store.delete("a"));
        assertTrue(store.findById("a").isEmpty());
        assertTrue(store.findById("b").isPresent());
    }

    @Test
    void deleteReportsWhenNothingWasRemoved() {
        InMemoryChatSessionStore store = new InMemoryChatSessionStore();

        assertFalse(store.delete("never-existed"));
    }

    @Test
    void findAllOnAnEmptyStoreIsEmptyRatherThanNull() {
        assertTrue(new InMemoryChatSessionStore().findAll().isEmpty());
    }

    private ChatSession chat(String id) {
        return new ChatSession(id, "mock", DepthMode.QUICK, "");
    }
}
