package com.debopam.llmcouncil;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class LlmCouncilApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoadsWithConfiguredProfilesPoliciesAndProtocols() {
    }

    @Test
    void theShippedConfigurationBootsWithNoDatasourceAtAll() {
        // council.persistence.type defaults to memory, and memory must cost
        // nothing. Boot's datasource auto-configuration reads the SQLite and H2
        // drivers on the classpath as an invitation to create an in-memory
        // database; a default install that quietly migrates a schema it never
        // writes to would work perfectly and so would never be reported.
        assertEquals(0, context.getBeanNamesForType(DataSource.class).length,
                     "the default install is council.persistence.type=memory, which must not "
                     + "create a database");
    }
}
