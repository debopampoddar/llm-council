package com.debopam.llmcouncil.application;

/** The in-memory store, held to the shared {@link EventStoreContractTest}. */
class InMemoryEventStoreContractTest extends EventStoreContractTest {

    @Override
    protected EventStore createStore() {
        return new InMemoryEventStore();
    }
}
