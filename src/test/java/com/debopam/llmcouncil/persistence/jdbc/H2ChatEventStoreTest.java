package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.persistence.jdbc.JdbcTestDatabase.Engine;

/** Durable chat events on H2, the fallback engine. */
class H2ChatEventStoreTest extends JdbcChatEventStoreContractTest {

    @Override
    protected Engine engine() {
        return Engine.H2;
    }
}
