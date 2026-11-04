package dev.schemaforge;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

@Service
public class QueryEngine {
    public record Argument(SchemaCatalog.Column column, Object value) {}
    public record Page(List<Map<String,Object>> data, boolean hasMore, String nextCursor) {}
    private record Where(List<String> clauses, List<Argument> args) {
        String sql() { return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses); }
    }
    private final SchemaCatalog catalog;
    private final JdbcTemplate jdbc;
    private final Access access;
    private final ForgeProperties config;
    private final ResponseCache cache;
    private static final Set<String> OPTIONS = Set.of("page", "limit", "sort", "select", "cursor");
    private static final Pattern OP = Pattern.compile("^(.+)\\[(eq|ne|gt|gte|lt|lte|like|isnull)\\]$");
    public QueryEngine(SchemaCatalog catalog, JdbcTemplate jdbc, Access access, ForgeProperties config, ResponseCache cache) {
        this.catalog = catalog; this.jdbc = jdbc; this.access = access; this.config = config; this.cache = cache;
        jdbc.setQueryTimeout(15);
        if (config.maxLimit() < 1 || config.maxLimit() > 10000) throw new IllegalStateException("Invalid max-limit");
    }
    private Where where(SchemaCatalog.Table table, Authentication auth) {
        Where w = new Where(new ArrayList<>(), new ArrayList<>());
        Object owner = access.owner(table, auth);
        if (owner != null) add(w, table.column(access.ownerColumn(table)), "=", owner);
        return w;
    }
    private void add(Where w, SchemaCatalog.Column c, String op, Object value) {
        w.clauses().add(catalog.q(c.name()) + " " + op + " ?");
        w.args().add(new Argument(c, value));
    }
    public Page list(String name, MultiValueMap<String,String> params, Authentication auth) {
        return list(catalog.table(name), params, auth, Map.of());
    }
    private Page list(SchemaCatalog.Table table, MultiValueMap<String,String> params, Authentication auth, Map<String,Object> related) {
        access.require(table, auth, false);
        Where w = where(table, auth);
        related.forEach((key, value) -> add(w, table.column(key), "=", Values.convert(table.column(key), value)));
        if (params.size() > 64) throw ApiException.bad("Too many query parameters");
        params.forEach((raw, values) -> {
            if (values.size() != 1) throw ApiException.bad("Repeated query parameter: " + raw);
            if (OPTIONS.contains(raw)) return;
            String column = raw, op = "eq", value = values.get(0);
            Matcher matcher = OP.matcher(raw);
            if (matcher.matches()) { column = matcher.group(1); op = matcher.group(2); }
            else if (value.isEmpty()) {
                Matcher shorthand = Pattern.compile("^([^<>]+)([<>])(.*)$").matcher(raw);
                if (shorthand.matches()) { column = shorthand.group(1); op = shorthand.group(2).equals(">") ? "gt" : "lt"; value = shorthand.group(3); }
            }
            var c = table.column(column);
            if (op.equals("isnull")) {
                if (!value.equals("true") && !value.equals("false")) throw ApiException.bad("isnull expects true or false");
                w.clauses().add(catalog.q(c.name()) + (value.equals("true") ? " IS NULL" : " IS NOT NULL"));
            } else {
                String sqlOp = switch(op) { case "ne" -> "<>"; case "gt" -> ">"; case "gte" -> ">="; case "lt" -> "<"; case "lte" -> "<="; case "like" -> "LIKE"; default -> "="; };
                if (op.equals("like") && !Set.of(Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR, Types.NVARCHAR).contains(c.type()))
                    throw ApiException.bad("LIKE requires a text column");
                add(w, c, sqlOp, Values.convert(c, value));
            }
        });
        int limit = number(params.getFirst("limit"), 20, 1, config.maxLimit());
        int page = number(params.getFirst("page"), 1, 1, 1000000);
        List<String> selected = params.containsKey("select") ? split(params.getFirst("select")) : new ArrayList<>(table.columns().keySet());
        selected.forEach(table::column);
        boolean cursor = params.containsKey("cursor");
        List<String> fetched = new ArrayList<>(selected);
        List<String> order = new ArrayList<>();
        if (cursor) {
            if (params.containsKey("sort") || params.containsKey("page")) throw ApiException.bad("Cursor pagination cannot be combined with sort or page");
            var pk = table.key();
            if (!fetched.contains(pk.name())) fetched.add(pk.name());
            order.add(catalog.q(pk.name()) + " ASC");
            String token = params.getFirst("cursor");
            if (!"first".equals(token)) {
                if (token == null || token.length() > 4096) throw ApiException.bad("Invalid cursor");
                try { add(w, pk, ">", Values.convert(pk, new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8))); }
                catch (IllegalArgumentException e) { throw ApiException.bad("Invalid cursor"); }
            }
        } else {
            Set<String> ordered = new HashSet<>();
            if (params.containsKey("sort")) for (String item : split(params.getFirst("sort"))) {
                boolean desc = item.startsWith("-");
                String col = desc ? item.substring(1) : item;
                table.column(col); ordered.add(col);
                order.add(catalog.q(col) + (desc ? " DESC" : " ASC"));
            }
            for (String pk : table.primaryKey()) if (ordered.add(pk)) order.add(catalog.q(pk) + " ASC");
            if (order.isEmpty()) order.add(catalog.q(table.columns().keySet().iterator().next()) + " ASC");
        }
        String sql = "SELECT " + String.join(",", fetched.stream().map(catalog::q).toList()) + " FROM " + catalog.qualified(table)
                + w.sql() + " ORDER BY " + String.join(",", order) + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        // MySQL uses LIMIT/OFFSET; PostgreSQL and Oracle support ANSI OFFSET/FETCH.
        boolean mysql = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName().equals("MySQL"));
        if (mysql) sql = sql.substring(0, sql.indexOf(" OFFSET ?")) + " LIMIT ? OFFSET ?";
        List<Argument> args = new ArrayList<>(w.args());
        var numeric = new SchemaCatalog.Column("pagination", Types.BIGINT, "BIGINT", false, false, 0);
        long offset = cursor ? 0 : (long)(page - 1) * limit;
        args.add(new Argument(numeric, mysql ? (long)limit + 1 : offset));
        args.add(new Argument(numeric, mysql ? offset : (long)limit + 1));
        String cacheKey = cache.key(table.name(), sql, args);
        Page cached = cache.get(cacheKey);
        if (cached != null) return cached;
        List<Map<String,Object>> rows = query(table, sql, args);
        boolean more = rows.size() > limit;
        if (more) rows.remove(rows.size() - 1);
        String next = cursor && more && !rows.isEmpty() ? Base64.getUrlEncoder().withoutPadding().encodeToString(
                rows.get(rows.size()-1).get(table.key().name()).toString().getBytes(StandardCharsets.UTF_8)) : null;
        rows.forEach(row -> row.keySet().retainAll(selected));
        Page result = new Page(rows, more, next);
        cache.put(cacheKey, result);
        return result;
    }
    private static int number(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try { int n = Integer.parseInt(value); if (n < min || n > max) throw new NumberFormatException(); return n; }
        catch (NumberFormatException e) { throw ApiException.bad("Pagination value out of range"); }
    }
    private static List<String> split(String value) {
        if (value == null || value.isBlank()) throw ApiException.bad("Empty projection or sort");
        List<String> result = Arrays.asList(value.split(",", -1));
        if (result.stream().anyMatch(String::isBlank) || new HashSet<>(result).size() != result.size()) throw ApiException.bad("Invalid projection or sort");
        return result;
    }
    private List<Map<String,Object>> query(SchemaCatalog.Table table, String sql, List<Argument> args) {
        return jdbc.query(c -> {
            var s = c.prepareStatement(sql); s.setQueryTimeout(15); bind(s, args); return s;
        }, (rs, rowNum) -> {
            Map<String,Object> row = new LinkedHashMap<>();
            for (int i=1; i<=rs.getMetaData().getColumnCount(); i++) {
                String name = rs.getMetaData().getColumnLabel(i);
                try {
                    Object v = Values.output(rs.getObject(i));
                    if (v instanceof String text && table.column(name).typeName().toLowerCase(Locale.ROOT).contains("json"))
                        v = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
                    row.put(name, v);
                } catch (Exception e) { throw new SQLException("Unsupported result value", e); }
            }
            return row;
        });
    }
    private void bind(PreparedStatement s, List<Argument> args) throws SQLException {
        for (int i=0; i<args.size(); i++) Values.bind(s, i+1, args.get(i).value(), args.get(i).column());
    }
    private Where itemWhere(SchemaCatalog.Table table, String id, Authentication auth) {
        Where w = where(table, auth); add(w, table.key(), "=", Values.convert(table.key(), id)); return w;
    }
    public Map<String,Object> item(String name, String id, Authentication auth) {
        var table = catalog.table(name); access.require(table, auth, false);
        return item(table, id, auth);
    }
    private Map<String,Object> item(SchemaCatalog.Table table, String id, Authentication auth) {
        var w = itemWhere(table, id, auth);
        var rows = query(table, "SELECT * FROM " + catalog.qualified(table) + w.sql(), w.args());
        if (rows.isEmpty()) throw new ApiException(404, "Row not found");
        return rows.get(0);
    }
    @Transactional
    public Map<String,Object> insert(String name, Map<String,Object> input, Authentication auth) {
        var table = catalog.table(name); access.require(table, auth, true);
        Map<String,Object> body = body(table, input, false);
        Object owner = access.owner(table, auth);
        if (owner != null) {
            String col = access.ownerColumn(table);
            if (body.containsKey(col) && !Objects.equals(body.get(col), owner)) throw new ApiException(403, "Row ownership cannot be changed");
            body.put(col, owner);
        }
        if (body.isEmpty()) throw ApiException.bad("Provide at least one writable column");
        String sql = "INSERT INTO " + catalog.qualified(table) + " (" + String.join(",", body.keySet().stream().map(catalog::q).toList())
                + ") VALUES (" + String.join(",", Collections.nCopies(body.size(), "?")) + ")";
        List<Argument> args = body.entrySet().stream().map(e -> new Argument(table.column(e.getKey()), e.getValue())).toList();
        org.springframework.jdbc.support.GeneratedKeyHolder keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        if (table.primaryKey().size() == 1) {
            jdbc.update(c -> {
                var statement = c.prepareStatement(sql, new String[]{table.key().name()});
                statement.setQueryTimeout(15); bind(statement, args); return statement;
            }, keys);
        } else {
            jdbc.update(c -> { var statement = c.prepareStatement(sql); statement.setQueryTimeout(15); bind(statement,args); return statement; });
        }
        cache.invalidateAfterCommit(table.name());
        Map<String,Object> result = new LinkedHashMap<>();
        if (!keys.getKeyList().isEmpty() && keys.getKeys() != null && !keys.getKeys().isEmpty()) {
            try { result.put(table.key().name(), Values.output(keys.getKeys().values().iterator().next())); }
            catch (Exception e) { throw new IllegalArgumentException("Unsupported generated key"); }
        }
        // Return keys only: write permission must not imply permission to read defaulted/secret columns.
        for (String key : table.primaryKey()) if (body.containsKey(key)) result.put(key, input.get(key));
        return result;
    }
    @Transactional
    public void update(String name, String id, Map<String,Object> input, boolean replace, Authentication auth) {
        var table = catalog.table(name); access.require(table, auth, true);
        Map<String,Object> body = body(table, input, true);
        Object owner = access.owner(table, auth);
        if (owner != null && body.containsKey(access.ownerColumn(table)) && !Objects.equals(owner, body.get(access.ownerColumn(table))))
            throw new ApiException(403, "Row ownership cannot be changed");
        if (replace) for (var col : table.columns().values()) {
            if (!col.generated() && !table.primaryKey().contains(col.name()) && !body.containsKey(col.name())
                    && !(owner != null && col.name().equals(access.ownerColumn(table))))
                throw ApiException.bad("PUT requires every writable non-key column: " + col.name());
        }
        if (body.isEmpty()) throw ApiException.bad("No writable columns");
        Where w = itemWhere(table, id, auth);
        List<Argument> args = new ArrayList<>();
        body.forEach((key, value) -> args.add(new Argument(table.column(key), value))); args.addAll(w.args());
        String sql = "UPDATE " + catalog.qualified(table) + " SET " + String.join(",", body.keySet().stream().map(k -> catalog.q(k) + " = ?").toList()) + w.sql();
        int count = jdbc.update(c -> { var s = c.prepareStatement(sql); s.setQueryTimeout(15); bind(s, args); return s; });
        if (count == 0) throw new ApiException(404, "Row not found");
        cache.invalidateAfterCommit(table.name());
    }
    @Transactional
    public void delete(String name, String id, Authentication auth) {
        var table = catalog.table(name); access.require(table, auth, true);
        var w = itemWhere(table, id, auth);
        int count = jdbc.update(c -> { var s = c.prepareStatement("DELETE FROM " + catalog.qualified(table) + w.sql()); s.setQueryTimeout(15); bind(s,w.args()); return s; });
        if (count == 0) throw new ApiException(404, "Row not found");
        cache.invalidateAfterCommit(table.name());
    }
    private Map<String,Object> body(SchemaCatalog.Table table, Map<String,Object> input, boolean update) {
        if (input == null || input.size() > 256) throw ApiException.bad("Invalid body");
        Map<String,Object> values = new LinkedHashMap<>();
        input.forEach((name, value) -> {
            var col = table.column(name);
            if (col.generated() || update && table.primaryKey().contains(name)) throw ApiException.bad("Column is not writable: " + name);
            values.put(name, Values.convert(col,value));
        });
        return values;
    }
    public Page related(String parentName, String id, String childName, MultiValueMap<String,String> params, Authentication auth) {
        var parent = catalog.table(parentName); var child = catalog.table(childName);
        access.require(parent, auth, false); access.require(child, auth, false);
        var matches = child.foreignKeys().stream().filter(f -> f.table().equals(parentName)).toList();
        if (matches.size() != 1) throw ApiException.bad("Relationship must have exactly one foreign key to the parent table");
        Map<String,Object> row = item(parent, id, auth);
        Map<String,Object> match = new LinkedHashMap<>();
        var fk = matches.get(0);
        for (int i=0; i<fk.columns().size(); i++) match.put(fk.columns().get(i), row.get(fk.referencedColumns().get(i)));
        return list(child, params, auth, match);
    }
}
