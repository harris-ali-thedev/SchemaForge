package dev.schemaforge;

import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("forge")
public record ForgeProperties(String schema, Set<String> tables, Security security, Map<String, Policy> policies,
                              int maxLimit, int requestsPerMinute, boolean cacheEnabled, int cacheSeconds) {
    public record Security(String jwtSecret, String issuer, String audience, List<ApiKey> apiKeys) {}
    public record ApiKey(String sha256, String subject, Set<String> roles) {}
    public record Policy(Set<String> read, Set<String> write, String rowColumn, String rowClaim) {}
}
