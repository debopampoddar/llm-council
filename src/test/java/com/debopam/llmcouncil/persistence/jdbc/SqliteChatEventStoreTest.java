package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.persistence.jdbc.JdbcTestDatabase.Engine;

/** Durable chat events on SQLite, the recommended engine. */
class SqliteChatEventStoreTest extends JdbcChatEventStoreContractTest {

    @Override
    protected Engine engine() {
        return Engine.SQLITE;
    }
}
