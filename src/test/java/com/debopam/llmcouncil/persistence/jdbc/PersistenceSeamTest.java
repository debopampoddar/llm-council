package com.debopam.llmcouncil.persistence.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code memory} / {@code jdbc} seam.
 *
 * <p>The invariant under test is that {@code memory} stays the default and costs
 * nothing. It is easy to get wrong in a way nothing notices: the SQLite and H2
 * drivers are on the classpath so that switching to {@code jdbc} needs no extra
 * install, and Boot's own datasource auto-configuration reads an embedded driver
 * on the classpath as an invitation to create an in-memory database. Every
 * default install would then be running a database and migrating a schema it
 * never writes to — which works, and so would never be reported as a bug.
 *
 * <p>Each "no datasource" assertion is therefore paired with a case that does
 * build one, so a seam that had stopped creating datasources entirely could not
 * pass this file.
 */
class PersistenceSeamTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                                                     JdbcTemplateAutoConfiguration.class))
            .withUserConfiguration(JdbcPersistenceConfig.class);

    @Test
    void anUnsetPersistenceTypeCreatesNoDatasource() {
        runner.run(context -> assertThat(context).doesNotHaveBean(DataSource.class));
    }

    @Test
    void theMemoryTypeCreatesNoDatasource() {
        runner.withPropertyValues("council.persistence.type=memory")
              .run(context -> assertThat(context).doesNotHaveBean(DataSource.class));
    }

    @Test
    void theJdbcTypeCreatesADatasourceAndAJdbcTemplate() {
        // The positive control for the two assertions above: the seam is capable
        // of producing a datasource, so their absence is the default at work
        // rather than a configuration class that no longer loads.
        runner.withPropertyValues("council.persistence.type=jdbc",
                                  "spring.datasource.url=jdbc:h2:mem:seam-test")
              .run(context -> {
                  assertThat(context).hasSingleBean(DataSource.class);
                  assertThat(context).hasSingleBean(JdbcTemplate.class);
              });
    }

    @Test
    void aSqliteFileGetsItsParentDirectoryCreated(@TempDir Path tempDir) {
        // A first run on a machine that has never held the database must not
        // need a manual mkdir: SQLite does not create missing directories, and
        // its failure names neither the directory nor the reason.
        Path database = tempDir.resolve("never-created/council.db");
        assertThat(Files.exists(database.getParent())).isFalse();

        runner.withPropertyValues("council.persistence.type=jdbc",
                                  "spring.datasource.url=jdbc:sqlite:" + database)
              .run(context -> {
                  assertThat(context).hasSingleBean(DataSource.class);
                  assertThat(Files.isDirectory(database.getParent())).isTrue();
              });
    }
}
