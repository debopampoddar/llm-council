package com.debopam.llmcouncil.persistence.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates the datasource the durable stores run on, and only then.
 *
 * <p>This is the whole of the {@code memory} / {@code jdbc} seam. There is no
 * datasource bean at all under the default {@code memory} setting, which is why
 * {@code DataSourceAutoConfiguration} is excluded in
 * {@code LlmCouncilApplication}: with a driver on the classpath and no explicit
 * URL, Boot would helpfully conjure an in-memory H2 database and migrate it,
 * giving every {@code memory} user a database they never asked for and cannot
 * see. Nothing here runs unless {@code council.persistence.type=jdbc}.
 *
 * <p>SQLite and H2 are not two implementations. They are this one bean with a
 * different URL and a different driver on the classpath; Postgres and MySQL
 * arrive later by the same route with no new Java.
 */
@Configuration
@ConditionalOnProperty(name = "council.persistence.type", havingValue = "jdbc")
public class JdbcPersistenceConfig {

    private static final Logger log = LoggerFactory.getLogger(JdbcPersistenceConfig.class);

    /** URL prefix identifying SQLite, which needs a single-connection pool. */
    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    private final String jdbcUrl;
    private final String username;
    private final String password;

    /**
     * @param jdbcUrl  the JDBC URL, from {@code spring.datasource.url}
     * @param username the database user, blank for the file-based engines
     * @param password the database password, blank for the file-based engines
     */
    public JdbcPersistenceConfig(@Value("${spring.datasource.url}") String jdbcUrl,
                                 @Value("${spring.datasource.username:}") String username,
                                 @Value("${spring.datasource.password:}") String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * Build the pooled datasource for the configured engine.
     *
     * <p>A SQLite pool is pinned to one connection. SQLite serialises writers at
     * the file level, so a larger pool does not buy concurrency — it converts
     * what would have been a brief wait into {@code SQLITE_BUSY} failures on the
     * losing connections, which on this write path would mean a lost council
     * event rather than a slow one.
     *
     * @return the datasource, with its parent directory created if it is a file
     */
    @Bean
    public DataSource councilDataSource() {
        createParentDirectoryIfFileBased();
        HikariDataSource dataSource = DataSourceBuilder.create()
                .url(jdbcUrl)
                .username(username.isBlank() ? null : username)
                .password(password.isBlank() ? null : password)
                .type(HikariDataSource.class)
                .build();
        dataSource.setPoolName("council-jdbc");
        if (isSqlite()) {
            dataSource.setMaximumPoolSize(1);
        }
        log.info("Durable persistence enabled: {}", redacted(jdbcUrl));
        return dataSource;
    }

    /**
     * Create the directory a file-based database will live in.
     *
     * <p>SQLite does not create missing directories: pointed at
     * {@code ~/.llm-council/council.db} on a machine that has never run the app,
     * it fails to open the file with an error that names neither the directory
     * nor the reason. A first run must not need a manual {@code mkdir}.
     */
    private void createParentDirectoryIfFileBased() {
        if (!isSqlite()) {
            return;
        }
        String filePath = jdbcUrl.substring(SQLITE_PREFIX.length());
        if (filePath.isBlank() || filePath.startsWith(":")) {
            return;
        }
        Path parent = Path.of(filePath).toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to create database directory " + parent, ex);
        }
    }

    private boolean isSqlite() {
        return jdbcUrl.startsWith(SQLITE_PREFIX);
    }

    /**
     * Strip any credentials a URL query string might carry before logging it.
     *
     * @param url the configured JDBC URL
     * @return the URL up to its first query separator
     */
    private static String redacted(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query) + "?…";
    }
}
