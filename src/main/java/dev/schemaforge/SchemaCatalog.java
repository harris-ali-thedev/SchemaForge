package dev.schemaforge;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/** Immutable, connection-scoped metadata snapshot. Only explicitly discovered identifiers reach SQL. */
@Component
@org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
public class SchemaCatalog {
    public record Column(String name, int type, String typeName, boolean nullable, boolean generated, int size) {}
    public record ForeignKey(String name, String table, List<String> columns, List<String> referencedColumns) {}
    public record Table(String name, Map<String, Column> columns, List<String> primaryKey, List<ForeignKey> foreignKeys) {
        public Column column(String name) {
            Column c = columns.get(name);
            if (c == null) throw ApiException.bad("Unknown column: " + name);
            return c;
        }
        public Column key() {
            if (primaryKey.size() != 1) throw ApiException.bad("This operation requires a single-column primary key");
            return column(primaryKey.get(0));
        }
    }
    private final Map<String, Table> tables;
    private final String quote;
    private final String schema;
    private final boolean mysql;
    public SchemaCatalog(DataSource source, ForgeProperties config) throws SQLException {
        Map<String, Table> found = new TreeMap<>();
        try (Connection connection = source.getConnection()) {
            DatabaseMetaData md = connection.getMetaData();
            quote = md.getIdentifierQuoteString().trim();
            mysql = md.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
            schema = config.schema() == null || config.schema().isBlank()
                    ? (mysql ? connection.getCatalog() : connection.getSchema()) : config.schema();
            if (schema == null || schema.isBlank()) throw new IllegalStateException("Set forge.schema / DB_SCHEMA");
            String catalog = mysql ? schema : connection.getCatalog();
            String pattern = mysql ? null : schema;
            try (ResultSet rs = md.getTables(catalog, mysql ? null : escape(md, schema), "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (config.tables() != null && !config.tables().isEmpty() && !config.tables().contains(name)) continue;
                    Map<String, Column> columns = new LinkedHashMap<>();
                    try (ResultSet cs = md.getColumns(catalog, mysql ? null : escape(md, schema), escape(md, name), "%")) {
                        while (cs.next()) {
                            String col = cs.getString("COLUMN_NAME");
                            columns.put(col, new Column(col, cs.getInt("DATA_TYPE"), cs.getString("TYPE_NAME"),
                                cs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                "YES".equals(cs.getString("IS_AUTOINCREMENT")) || "YES".equals(cs.getString("IS_GENERATEDCOLUMN")), cs.getInt("COLUMN_SIZE")));
                        }
                    }
                    TreeMap<Integer, String> keys = new TreeMap<>();
                    try (ResultSet ks = md.getPrimaryKeys(catalog, pattern, name)) {
                        while (ks.next()) keys.put(ks.getInt("KEY_SEQ"), ks.getString("COLUMN_NAME"));
                    }
                    Map<String, TreeMap<Integer, String[]>> fkParts = new LinkedHashMap<>();
                    Map<String, String> targets = new HashMap<>();
                    try (ResultSet fs = md.getImportedKeys(catalog, pattern, name)) {
                        while (fs.next()) {
                            String targetSchema = fs.getString(mysql ? "PKTABLE_CAT" : "PKTABLE_SCHEM");
                            if (!schema.equals(targetSchema)) continue;
                            String key = fs.getString("FK_NAME");
                            if (key == null) continue;
                            targets.put(key, fs.getString("PKTABLE_NAME"));
                            fkParts.computeIfAbsent(key, k -> new TreeMap<>()).put(fs.getInt("KEY_SEQ"),
                                    new String[]{fs.getString("FKCOLUMN_NAME"), fs.getString("PKCOLUMN_NAME")});
                        }
                    }
                    List<ForeignKey> fks = new ArrayList<>();
                    fkParts.forEach((key, parts) -> fks.add(new ForeignKey(key, targets.get(key),
                        parts.values().stream().map(v -> v[0]).toList(), parts.values().stream().map(v -> v[1]).toList())));
                    found.put(name, new Table(name, Collections.unmodifiableMap(columns), List.copyOf(keys.values()), List.copyOf(fks)));
                }
            }
        }
        tables = Collections.unmodifiableMap(found);
        if (tables.isEmpty()) throw new IllegalStateException("No tables discovered; check DB_SCHEMA, DB_TABLES and database privileges");
    }
    private static String escape(DatabaseMetaData md, String name) throws SQLException {
        String e = md.getSearchStringEscape();
        return name.replace(e, e + e).replace("_", e + "_").replace("%", e + "%");
    }
    public Collection<Table> all() { return tables.values(); }
    public Table table(String name) {
        Table t = tables.get(name);
        if (t == null) throw new ApiException(404, "Unknown table");
        return t;
    }
    public String q(String identifier) { return quote + identifier.replace(quote, quote + quote) + quote; }
    public String qualified(Table table) { return q(schema) + "." + q(table.name()); }
}
