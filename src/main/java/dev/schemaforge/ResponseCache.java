package dev.schemaforge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;

/** Optional, bounded-staleness cache. Keys include the complete SQL predicate (including RLS) and bound values. */
@Component
public class ResponseCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ForgeProperties config;
    public ResponseCache(StringRedisTemplate redis, ObjectMapper json, ForgeProperties config) { this.redis=redis; this.json=json; this.config=config; }
    public String key(String table, String sql, List<QueryEngine.Argument> args) {
        if (!config.cacheEnabled()) return null;
        try {
            String epoch = redis.opsForValue().get("forge:epoch:" + table);
            String payload = json.writeValueAsString(List.of(sql, args.stream().map(a -> Arrays.asList(a.column().type(), a.value())).toList()));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
            return "forge:rows:" + table + ":" + epoch + ":" + hash;
        } catch (Exception e) { return null; }
    }
    public QueryEngine.Page get(String key) {
        if (key == null) return null;
        try { String text = redis.opsForValue().get(key); return text == null ? null : json.readValue(text, QueryEngine.Page.class); }
        catch (Exception e) { return null; }
    }
    public void put(String key, QueryEngine.Page value) {
        if (key == null) return;
        try { redis.opsForValue().set(key, json.writeValueAsString(value), Duration.ofSeconds(Math.max(1,config.cacheSeconds()))); }
        catch (Exception ignored) { /* Redis is optional. Database remains the source of truth. */ }
    }
    public void invalidateAfterCommit(String table) {
        if (!config.cacheEnabled()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { redis.opsForValue().increment("forge:epoch:" + table); }
                catch (Exception ignored) { /* Existing values expire at their bounded TTL. */ }
            }
        });
    }
}
