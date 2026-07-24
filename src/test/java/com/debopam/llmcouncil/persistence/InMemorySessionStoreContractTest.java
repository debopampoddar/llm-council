package com.debopam.llmcouncil.persistence;

/** The in-memory store, held to the shared {@link SessionStoreContractTest}. */
class InMemorySessionStoreContractTest extends SessionStoreContractTest {

    @Override
    protected SessionStore createStore() {
        return new InMemorySessionStore();
    }
}
