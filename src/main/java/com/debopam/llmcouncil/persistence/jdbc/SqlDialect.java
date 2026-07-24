package com.debopam.llmcouncil.persistence.jdbc;

import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;

/**
 * The only engine-specific SQL in the durable stores.
 *
 * <p>Everything else is written to the intersection of the supported engines.
 * Upsert is the exception, because there is no portable spelling of it that both
 * H2 and SQLite accept: H2 has {@code MERGE INTO … KEY(id)} and no
 * {@code ON CONFLICT}, SQLite has {@code ON CONFLICT} and no {@code MERGE}.
 *
 * <p>A single-statement upsert is worth the dialect. The alternative — try an
 * {@code UPDATE}, {@code INSERT} when it affects no rows — has a window between
 * the two statements, and the writers here are virtual threads finishing council
 * runs, so a chat saved from a completing run and from the request thread at the
 * same moment would occasionally fail on a primary-key violation. Rare, timing
 * dependent, and impossible to reproduce on request: the worst shape a bug can
 * have.
 */
public enum SqlDialect {

    /** H2, which spells upsert {@code MERGE INTO … KEY(column)}. */
    H2 {
        @Override
        public String upsert(String table, String keyColumn, List<String> columns) {
            return "MERGE INTO " + table + " (" + String.join(", ", columns) + ") "
                   + "KEY (" + keyColumn + ") VALUES (" + placeholders(columns.size()) + ")";
        }
    },

    /** SQLite, which spells it {@code INSERT … ON CONFLICT DO UPDATE}. */
    SQLITE {
        @Override
        public String upsert(String table, String keyColumn, List<String> columns) {
            return onConflictUpsert(table, keyColumn, columns);
        }
    },

    /**
     * Anything else, assumed to speak the SQL-standard {@code ON CONFLICT} form
     * that Postgres uses.
     *
     * <p>This is what makes "Postgres comes free later" true rather than
     * aspirational. MySQL does not belong here — it spells the same operation
     * {@code ON DUPLICATE KEY UPDATE} — and would need its own constant.
     */
    GENERIC {
        @Override
        public String upsert(String table, String keyColumn, List<String> columns) {
            return onConflictUpsert(table, keyColumn, columns);
        }
    };

    /**
     * Build an insert-or-replace statement for the given columns, in order.
     *
     * @param table     the target table
     * @param keyColumn the primary key column
     * @param columns   every column being written, key column included
     * @return SQL taking one bind parameter per column, in the listed order
     */
    public abstract String upsert(String table, String keyColumn, List<String> columns);

    /**
     * Identify the engine behind a datasource.
     *
     * <p>Read from {@code DatabaseMetaData} rather than from the JDBC URL, so a
     * URL that reaches an engine by an unexpected spelling still resolves to the
     * right dialect.
     *
     * @param dataSource the datasource to inspect
     * @return the matching dialect, or {@link #GENERIC} when the product is
     *         unrecognised or cannot be read
     */
    public static SqlDialect detect(DataSource dataSource) {
        String product;
        try {
            product = JdbcUtils.extractDatabaseMetaData(dataSource, "getDatabaseProductName");
        } catch (MetaDataAccessException ex) {
            return GENERIC;
        }
        if (product == null) {
            return GENERIC;
        }
        String normalised = product.toLowerCase(Locale.ROOT);
        if (normalised.contains("h2")) {
            return H2;
        }
        if (normalised.contains("sqlite")) {
            return SQLITE;
        }
        return GENERIC;
    }

    /**
     * The {@code ON CONFLICT} spelling, shared by SQLite and the generic case.
     *
     * @param table     the target table
     * @param keyColumn the primary key column
     * @param columns   every column being written
     * @return the upsert statement
     */
    private static String onConflictUpsert(String table, String keyColumn, List<String> columns) {
        String assignments = columns.stream()
                .filter(column -> !column.equals(keyColumn))
                .map(column -> column + " = excluded." + column)
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Upsert on " + table + " needs at least one non-key column"));
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") "
               + "VALUES (" + placeholders(columns.size()) + ") "
               + "ON CONFLICT (" + keyColumn + ") DO UPDATE SET " + assignments;
    }

    /**
     * @param count how many bind parameters are needed
     * @return {@code "?, ?, …"} with that many placeholders
     */
    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}
