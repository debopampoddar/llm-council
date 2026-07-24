package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.persistence.jdbc.JdbcTestDatabase.Engine;

/** The event-store contract on SQLite, the recommended engine. */
class SqliteEventStoreTest extends JdbcEventStoreContractTest {

    @Override
    protected Engine engine() {
        return Engine.SQLITE;
    }
}
