package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.persistence.jdbc.JdbcTestDatabase.Engine;

/** The chat-store contract on SQLite, the recommended engine. */
class SqliteChatSessionStoreTest extends JdbcChatSessionStoreContractTest {

    @Override
    protected Engine engine() {
        return Engine.SQLITE;
    }
}
