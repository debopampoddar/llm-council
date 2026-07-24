package com.debopam.llmcouncil.persistence.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Real databases for the durable-store tests, on a temp file per test.
 *
 * <p>Both engines are ordinary jars. There is no daemon, no container, no
 * network and no fixed port, so {@code mvn test} stays hermetic —
 * Testcontainers is deliberately not part of the default build, because
 * MySQL and Postgres would exercise the same code path H2 already covers.
 *
 * <p>The schema is created by running the real Flyway migrations, not by a
 * hand-written DDL fixture. A fixture that drifted from the shipped migration
 * would let every store test pass against a schema no user has.
 */
final class JdbcTestDatabase {

    private JdbcTestDatabase() {
    }

    /** The engines every store contract is checked against. */
    enum Engine {

        /** File-mode H2, the fallback for anyone avoiding a native dependency. */
        H2 {
            @Override
            String url(Path directory) {
                return "jdbc:h2:file:" + directory.resolve("council") + ";DB_CLOSE_DELAY=-1";
            }
        },

        /** SQLite, the recommended engine: one file, no daemon, no port. */
        SQLITE {
            @Override
            String url(Path directory) {
                return "jdbc:sqlite:" + directory.resolve("council.db");
            }
        };

        abstract String url(Path directory);
    }

    /**
     * Build a migrated datasource on a fresh file.
     *
     * @param engine    which engine to run
     * @param directory a per-test temporary directory
     * @return a datasource whose schema is at the latest migration
     */
    static DataSource migrated(Engine engine, Path directory) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .url(engine.url(directory))
                .type(HikariDataSource.class)
                .build();
        // SQLite serialises writers at the file level; one connection avoids
        // SQLITE_BUSY exactly as the production configuration does.
        dataSource.setMaximumPoolSize(engine == Engine.SQLITE ? 1 : 4);
        Flyway.configure()
              .dataSource(dataSource)
              .locations("classpath:db/migration")
              .load()
              .migrate();
        return dataSource;
    }

    /**
     * Build a migrated {@link JdbcTemplate}.
     *
     * @param engine    which engine to run
     * @param directory a per-test temporary directory
     * @return a template over a migrated database
     */
    static JdbcTemplate migratedTemplate(Engine engine, Path directory) {
        return new JdbcTemplate(migrated(engine, directory));
    }
}
