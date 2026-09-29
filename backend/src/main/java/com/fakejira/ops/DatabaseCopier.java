package com.fakejira.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies every table from one database to another with plain JDBC, e.g. from the built-in H2 file to PostgreSQL.
 * The target schema must already exist (Hibernate creates it on start); tables are filled parents-first.
 */
public final class DatabaseCopier {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCopier.class);
    private static final int BATCH = 500;

    private DatabaseCopier() {
    }

    public record Report(Map<String, Integer> rows) {
        public int total() {
            return rows.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** Copies all rows; the target's existing rows are removed first. Runs in one target transaction. */
    public static Report copy(Connection source, Connection target) throws SQLException {
        Map<String, String> sourceTables = tables(source);
        Map<String, String> targetTables = tables(target);
        List<String> order = dependencyOrder(target, targetTables);
        boolean autoCommit = target.getAutoCommit();
        target.setAutoCommit(false);
        Map<String, Integer> copied = new LinkedHashMap<>();
        try {
            for (int i = order.size() - 1; i >= 0; i--) {
                try (Statement st = target.createStatement()) {
                    st.executeUpdate("DELETE FROM " + quote(target, targetTables.get(order.get(i))));
                }
            }
            for (String table : order) {
                String sourceName = sourceTables.get(table);
                if (sourceName == null) {
                    continue;
                }
                copied.put(table, copyTable(source, sourceName, target, targetTables.get(table)));
            }
            if (isPostgres(target)) {
                resetSequences(target, targetTables, order);
            }
            target.commit();
        } catch (SQLException | RuntimeException e) {
            target.rollback();
            throw e;
        } finally {
            target.setAutoCommit(autoCommit);
        }
        return new Report(copied);
    }

    /**
     * Writes every row as portable INSERT statements (parents first), plus sequence resets for PostgreSQL.
     * Restore into an empty database whose schema the app has created: {@code psql -f database.sql}.
     */
    public static int dumpSql(Connection connection, java.io.Writer out) throws SQLException, java.io.IOException {
        Map<String, String> tables = tables(connection);
        List<String> order = dependencyOrder(connection, tables);
        int rows = 0;
        out.write("-- FakeJIRA data dump. Load into a database whose tables the app has created.\nBEGIN;\n");
        // Rows the app may have written on its first start are replaced.
        for (int i = order.size() - 1; i >= 0; i--) {
            out.write("DELETE FROM " + order.get(i) + ";\n");
        }
        for (String table : order) {
            String name = tables.get(table);
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + quote(connection, name)
                    + (columns(connection, name).contains("id") ? " ORDER BY id" : ""))) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> cols = new ArrayList<>();
                for (int c = 1; c <= meta.getColumnCount(); c++) {
                    cols.add(quoteSafe(connection, meta.getColumnName(c).toLowerCase(Locale.ROOT)));
                }
                String prefix = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES (";
                while (rs.next()) {
                    StringBuilder line = new StringBuilder(prefix);
                    for (int c = 1; c <= cols.size(); c++) {
                        line.append(c > 1 ? ", " : "").append(literal(rs.getObject(c)));
                    }
                    out.write(line.append(");\n").toString());
                    rows++;
                }
            }
        }
        if (isPostgres(connection)) {
            for (String table : order) {
                if (columns(connection, tables.get(table)).contains("id")) {
                    out.write("SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), COALESCE((SELECT MAX(id) FROM " + table
                            + "), 1), (SELECT MAX(id) IS NOT NULL FROM " + table + "));\n");
                }
            }
        }
        out.write("COMMIT;\n");
        return rows;
    }

    static String literal(Object value) throws SQLException {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof java.sql.Clob clob) {
            value = clob.getSubString(1, (int) clob.length());
        }
        if (value instanceof byte[] bytes) {
            return "'\\x" + java.util.HexFormat.of().formatHex(bytes) + "'";
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            value = odt.toString();
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    /** Rows in the users table (0 means a fresh install). */
    public static int userCount(Connection connection) throws SQLException {
        String users = tables(connection).get("users");
        if (users == null) {
            return 0;
        }
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + quote(connection, users))) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int copyTable(Connection source, String sourceName, Connection target, String targetName) throws SQLException {
        Set<String> targetColumns = columns(target, targetName);
        List<String> shared = new ArrayList<>();
        String orderBy = null;
        try (Statement st = source.createStatement();
             ResultSet probe = st.executeQuery("SELECT * FROM " + quote(source, sourceName) + " WHERE 1 = 0")) {
            ResultSetMetaData meta = probe.getMetaData();
            for (int c = 1; c <= meta.getColumnCount(); c++) {
                String column = meta.getColumnName(c).toLowerCase(Locale.ROOT);
                if (targetColumns.contains(column)) {
                    shared.add(column);
                }
                if (column.equals("id")) {
                    orderBy = column;
                }
            }
        }
        if (shared.isEmpty()) {
            return 0;
        }
        String select = "SELECT " + String.join(", ", shared.stream().map(c -> quoteSafe(source, c)).toList())
                + " FROM " + quote(source, sourceName) + (orderBy == null ? "" : " ORDER BY " + quoteSafe(source, orderBy));
        String insert = "INSERT INTO " + quote(target, targetName) + " ("
                + String.join(", ", shared.stream().map(c -> quoteSafe(target, c)).toList()) + ") VALUES ("
                + String.join(", ", shared.stream().map(c -> "?").toList()) + ")";
        int count = 0;
        try (Statement st = source.createStatement(); ResultSet rs = st.executeQuery(select);
             PreparedStatement ps = target.prepareStatement(insert)) {
            while (rs.next()) {
                for (int c = 1; c <= shared.size(); c++) {
                    Object value = rs.getObject(c);
                    if (value instanceof java.sql.Clob clob) {
                        value = clob.getSubString(1, (int) clob.length());
                    }
                    ps.setObject(c, value);
                }
                ps.addBatch();
                if (++count % BATCH == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        log.info("Copied {} rows into {}", count, targetName);
        return count;
    }

    /** Lower-case table name → actual name, for ordinary tables of the current schema. */
    static Map<String, String> tables(Connection connection) throws SQLException {
        Map<String, String> names = new HashMap<>();
        DatabaseMetaData meta = connection.getMetaData();
        String schema = connection.getSchema();
        try (ResultSet rs = meta.getTables(connection.getCatalog(), schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                names.put(name.toLowerCase(Locale.ROOT), name);
            }
        }
        return names;
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException {
        Set<String> names = new HashSet<>();
        try (ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(), connection.getSchema(), table, "%")) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /** Tables ordered so that referenced tables come before the tables pointing at them (self-references ignored). */
    static List<String> dependencyOrder(Connection connection, Map<String, String> tables) throws SQLException {
        Map<String, Set<String>> parents = new HashMap<>();
        DatabaseMetaData meta = connection.getMetaData();
        for (Map.Entry<String, String> table : tables.entrySet()) {
            Set<String> refs = new HashSet<>();
            try (ResultSet rs = meta.getImportedKeys(connection.getCatalog(), connection.getSchema(), table.getValue())) {
                while (rs.next()) {
                    String parent = rs.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT);
                    if (!parent.equals(table.getKey())) {
                        refs.add(parent);
                    }
                }
            }
            parents.put(table.getKey(), refs);
        }
        List<String> order = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        List<String> remaining = new ArrayList<>(tables.keySet());
        remaining.sort(String::compareTo);
        while (!remaining.isEmpty()) {
            boolean progress = false;
            for (String table : new ArrayList<>(remaining)) {
                if (placed.containsAll(parents.get(table).stream().filter(tables::containsKey).toList())) {
                    order.add(table);
                    placed.add(table);
                    remaining.remove(table);
                    progress = true;
                }
            }
            if (!progress) {
                throw new IllegalStateException("Circular foreign keys between " + remaining);
            }
        }
        return order;
    }

    private static void resetSequences(Connection target, Map<String, String> tables, List<String> order) throws SQLException {
        for (String table : order) {
            String name = tables.get(table);
            if (!columns(target, name).contains("id")) {
                continue;
            }
            try (Statement st = target.createStatement();
                 ResultSet rs = st.executeQuery("SELECT pg_get_serial_sequence('" + quote(target, name).replace("'", "''") + "', 'id')")) {
                if (rs.next() && rs.getString(1) != null) {
                    String sequence = rs.getString(1);
                    try (Statement set = target.createStatement()) {
                        set.execute("SELECT setval('" + sequence.replace("'", "''") + "', COALESCE((SELECT MAX(id) FROM "
                                + quote(target, name) + "), 1), (SELECT MAX(id) IS NOT NULL FROM " + quote(target, name) + "))");
                    }
                }
            }
        }
    }

    static boolean isPostgres(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
    }

    private static String quote(Connection connection, String identifier) throws SQLException {
        String q = connection.getMetaData().getIdentifierQuoteString();
        return q == null || q.isBlank() ? identifier : q + identifier + q;
    }

    /** Column names are stored lower-case in PostgreSQL and upper-case in H2; unquoted names match both. */
    private static String quoteSafe(Connection connection, String column) {
        return column.matches("[a-z_][a-z0-9_]*") ? column : "\"" + column + "\"";
    }
}
