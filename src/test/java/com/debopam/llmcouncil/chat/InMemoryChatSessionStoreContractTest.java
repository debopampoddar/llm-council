package com.debopam.llmcouncil.chat;

/** The in-memory store, held to the shared {@link ChatSessionStoreContractTest}. */
class InMemoryChatSessionStoreContractTest extends ChatSessionStoreContractTest {

    @Override
    protected ChatSessionStore createStore() {
        return new InMemoryChatSessionStore();
    }
}
