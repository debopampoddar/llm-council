package com.debopam.llmcouncil.persistence.jdbc;

import com.debopam.llmcouncil.persistence.jdbc.JdbcTestDatabase.Engine;

/** The session-store contract on H2, the fallback engine. */
class H2SessionStoreTest extends JdbcSessionStoreContractTest {

    @Override
    protected Engine engine() {
        return Engine.H2;
    }
}
