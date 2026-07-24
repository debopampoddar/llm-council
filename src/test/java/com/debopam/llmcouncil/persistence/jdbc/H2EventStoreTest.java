package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.persistence.jdbc.JdbcTestDatabase.Engine;

/** The event-store contract on H2, the fallback engine. */
class H2EventStoreTest extends JdbcEventStoreContractTest {

    @Override
    protected Engine engine() {
        return Engine.H2;
    }
}
