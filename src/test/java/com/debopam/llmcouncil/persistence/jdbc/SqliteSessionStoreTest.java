package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.persistence.jdbc.JdbcTestDatabase.Engine;

/** The session-store contract on SQLite, the recommended engine. */
class SqliteSessionStoreTest extends JdbcSessionStoreContractTest {

    @Override
    protected Engine engine() {
        return Engine.SQLITE;
    }
}
