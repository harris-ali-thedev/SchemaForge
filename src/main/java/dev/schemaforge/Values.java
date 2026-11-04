package dev.schemaforge;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Values {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Values() {}
    public static Object convert(SchemaCatalog.Column col, Object input) {
        if (input == null) {
            if (!col.nullable() && !col.generated()) throw ApiException.bad("Null not allowed: " + col.name());
            return null;
        }
        try {
            String s = input.toString();
            if (col.typeName().equalsIgnoreCase("uuid")) return UUID.fromString(s);
            if (col.typeName().toLowerCase(Locale.ROOT).contains("json")) {
                String text = input instanceof String ? s : JSON.writeValueAsString(input);
                JSON.readTree(text);
                return text;
            }
            return switch (col.type()) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> new BigDecimal(s).intValueExact();
                case Types.BIGINT -> new BigDecimal(s).longValueExact();
                case Types.DECIMAL, Types.NUMERIC -> new BigDecimal(s);
                case Types.FLOAT, Types.REAL, Types.DOUBLE -> { double d = Double.parseDouble(s); if (!Double.isFinite(d)) throw new IllegalArgumentException(); yield d; }
                case Types.BOOLEAN, Types.BIT -> { if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) throw new IllegalArgumentException(); yield Boolean.valueOf(s); }
                case Types.DATE -> java.sql.Date.valueOf(LocalDate.parse(s));
                case Types.TIME -> Time.valueOf(LocalTime.parse(s));
                case Types.TIME_WITH_TIMEZONE -> OffsetTime.parse(s);
                case Types.TIMESTAMP -> Timestamp.valueOf(LocalDateTime.parse(s.replace(' ', 'T')));
                case Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.parse(s);
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> Base64.getDecoder().decode(s);
                default -> {
                    if (!(input instanceof String)) throw new IllegalArgumentException();
                    if (col.size() > 0 && s.length() > col.size()) throw new IllegalArgumentException();
                    yield s;
                }
            };
        } catch (Exception e) { throw ApiException.bad("Invalid value for column: " + col.name()); }
    }
    public static Object output(Object v) throws Exception {
        if (v instanceof Blob b) { try { return Base64.getEncoder().encodeToString(b.getBytes(1, Math.toIntExact(b.length()))); } finally { b.free(); } }
        if (v instanceof Clob c) { try { return c.getSubString(1, Math.toIntExact(c.length())); } finally { c.free(); } }
        if (v instanceof byte[] b) return Base64.getEncoder().encodeToString(b);
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime().toString();
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof java.sql.Time t) return t.toLocalTime().toString();
        if (v instanceof java.time.temporal.TemporalAccessor || v instanceof UUID) return v.toString();
        if (v != null && v.getClass().getName().equals("org.postgresql.util.PGobject")) {
            String type = (String)v.getClass().getMethod("getType").invoke(v);
            String value = (String)v.getClass().getMethod("getValue").invoke(v);
            return type.equals("json") || type.equals("jsonb") ? JSON.readTree(value) : value;
        }
        return v;
    }
    public static void bind(PreparedStatement statement, int index, Object value, SchemaCatalog.Column column) throws SQLException {
        if (value == null) statement.setNull(index, column.type());
        else if (column.typeName().equalsIgnoreCase("jsonb") || column.typeName().equalsIgnoreCase("json")) {
            if (statement.getConnection().getMetaData().getDatabaseProductName().equals("PostgreSQL")) statement.setObject(index, value, Types.OTHER);
            else statement.setString(index, value.toString());
        } else statement.setObject(index, value);
    }
}
