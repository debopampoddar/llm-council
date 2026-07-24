package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.persistence.jdbc.JdbcTestDatabase.Engine;

/** The chat-store contract on H2, the fallback engine. */
class H2ChatSessionStoreTest extends JdbcChatSessionStoreContractTest {

    @Override
    protected Engine engine() {
        return Engine.H2;
    }
}
