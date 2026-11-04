package dev.schemaforge;

import java.sql.Types;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class OpenApiController {
    private final SchemaCatalog catalog;
    private final Access access;
    public OpenApiController(SchemaCatalog catalog, Access access) { this.catalog=catalog; this.access=access; }
    @GetMapping("/openapi.json")
    public Map<String,Object> spec(Authentication auth) {
        Map<String,Object> paths = new LinkedHashMap<>(), schemas = new LinkedHashMap<>();
        for (var table : catalog.all()) {
            boolean read = allowed(table, auth, false), write = allowed(table, auth, true);
            if (!read && !write) continue;
            Map<String,Object> columns = new LinkedHashMap<>(), writable = new LinkedHashMap<>();
            table.columns().forEach((name, c) -> {
                columns.put(name, schema(c));
                if (!c.generated()) writable.put(name, schema(c));
            });
            schemas.put(table.name(), Map.of("type", "object", "properties", columns, "additionalProperties", false));
            schemas.put(table.name()+"Input", Map.of("type", "object", "properties", writable, "additionalProperties", false));
            String base = "/api/v1/" + table.name();
            Map<String,Object> collection = new LinkedHashMap<>(), item = new LinkedHashMap<>();
            if (read) {
                var get = operation("List " + table.name(), "200", Map.of("type", "object", "properties", Map.of(
                    "data", Map.of("type", "array", "items", ref(table.name())), "hasMore", Map.of("type", "boolean"), "nextCursor", Map.of("type", "string", "nullable", true))));
                List<Map<String,Object>> params = new ArrayList<>();
                params.add(parameter("limit", "query", "integer", false, "Page size (bounded by configured max-limit)"));
                params.add(parameter("page", "query", "integer", false, "One-based offset page"));
                params.add(parameter("cursor", "query", "string", false, "Use first, then nextCursor; single-column PK, ascending; excludes page/sort"));
                params.add(parameter("sort", "query", "string", false, "Comma-separated columns; -column means descending"));
                params.add(parameter("select", "query", "string", false, "Comma-separated projection"));
                table.columns().forEach((name,c) -> params.add(parameter(name, "query", "string", false,
                    "Equality filter. Also supports " + name + "[ne|gt|gte|lt|lte|like|isnull]")));
                get.put("parameters", params); collection.put("get",get);
                if (table.primaryKey().size() == 1) item.put("get", operation("Fetch " + table.name(), "200", ref(table.name())));
            }
            if (write) {
                var post = operation("Insert " + table.name(), "201", Map.of("type", "object", "description", "Inserted primary key; fields are not returned without a read operation"));
                post.put("requestBody", body(table.name()+"Input")); collection.put("post",post);
                if (table.primaryKey().size() == 1) {
                    for (String method : List.of("patch", "put")) {
                        var update = operation((method.equals("put") ? "Replace all writable non-key fields in " : "Update fields in ") + table.name(), "204", null);
                        update.put("requestBody", body(table.name()+"Input")); item.put(method,update);
                    }
                    item.put("delete", operation("Delete " + table.name(), "204",null));
                }
            }
            paths.put(base, collection);
            if (!item.isEmpty()) {
                item.put("parameters", List.of(parameter("id", "path", "string", true, "Primary key")));
                paths.put(base+"/{id}",item);
            }
            if (read && table.primaryKey().size() == 1) for (var child : catalog.all()) {
                if (!allowed(child,auth,false) || child.foreignKeys().stream().filter(f -> f.table().equals(table.name())).count() != 1) continue;
                var op = operation("List related " + child.name(), "200", Map.of("type", "object", "properties", Map.of("data", Map.of("type","array","items",ref(child.name())), "hasMore", Map.of("type","boolean"), "nextCursor",Map.of("type","string","nullable",true))));
                op.put("parameters", List.of(parameter("id","path","string",true,"Parent primary key")));
                paths.put(base+"/{id}/"+child.name(), Map.of("get",op));
            }
        }
        return Map.of("openapi", "3.0.3", "info", Map.of("title","SchemaForge API","version","1.0"), "paths", paths,
            "components", Map.of("schemas",schemas,"securitySchemes",Map.of("bearerAuth",Map.of("type","http","scheme","bearer","bearerFormat","JWT"),
                "apiKey",Map.of("type","apiKey","in","header","name","X-API-KEY"))), "security", List.of(Map.of("bearerAuth",List.of()),Map.of("apiKey",List.of())));
    }
    private boolean allowed(SchemaCatalog.Table t, Authentication auth, boolean write) {
        try { access.require(t,auth,write); return true; } catch(ApiException e) { return false; }
    }
    private Map<String,Object> ref(String name) { return Map.of("$ref", "#/components/schemas/" + name.replace("~","~0").replace("/","~1")); }
    private Map<String,Object> body(String name) { return Map.of("required",true,"content",Map.of("application/json",Map.of("schema",ref(name)))); }
    private Map<String,Object> parameter(String name, String in, String type, boolean required, String description) {
        return Map.of("name",name,"in",in,"required",required,"description",description,"schema",Map.of("type",type));
    }
    private Map<String,Object> operation(String summary, String success, Map<String,Object> schema) {
        Map<String,Object> op = new LinkedHashMap<>(); op.put("summary",summary);
        Map<String,Object> responses = new LinkedHashMap<>();
        responses.put(success,schema == null ? Map.of("description","Success") : Map.of("description","Success","content",Map.of("application/json",Map.of("schema",schema))));
        for (String status : List.of("400","401","403","404","409","429","503")) responses.put(status,Map.of("description",switch(status) {
            case "400" -> "Invalid request"; case "401" -> "Authentication required"; case "403" -> "Forbidden"; case "404" -> "Not found";
            case "409" -> "Constraint conflict"; case "429" -> "Rate limit exceeded"; default -> "Database unavailable";
        }));
        op.put("responses",responses); return op;
    }
    private Map<String,Object> schema(SchemaCatalog.Column c) {
        Map<String,Object> s = new LinkedHashMap<>();
        String type = switch(c.type()) {
            case Types.INTEGER,Types.BIGINT,Types.SMALLINT,Types.TINYINT -> "integer";
            case Types.NUMERIC,Types.DECIMAL,Types.FLOAT,Types.REAL,Types.DOUBLE -> "number";
            case Types.BOOLEAN,Types.BIT -> "boolean";
            default -> c.typeName().toLowerCase(Locale.ROOT).contains("json") ? "object" : "string";
        };
        s.put("type",type); s.put("nullable",c.nullable()); s.put("readOnly",c.generated());
        if (c.typeName().equalsIgnoreCase("uuid")) s.put("format","uuid");
        if (c.type() == Types.DATE) s.put("format","date");
        if (c.type() == Types.TIMESTAMP_WITH_TIMEZONE) s.put("format","date-time");
        if (Set.of(Types.BLOB,Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY).contains(c.type())) s.put("format","byte");
        return s;
    }
}
