package dev.schemaforge;

import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class Access {
    private final ForgeProperties config;
    public Access(ForgeProperties config) { this.config = config; }
    public ForgeProperties.Policy policy(SchemaCatalog.Table table) {
        if (config.policies() == null) return null;
        return config.policies().getOrDefault(table.name(), config.policies().get("*"));
    }
    public void require(SchemaCatalog.Table table, Authentication auth, boolean write) {
        var p = policy(table);
        Set<String> allowed = p == null ? Set.of() : write ? p.write() : p.read();
        if (auth == null || allowed == null || auth.getAuthorities().stream().noneMatch(a -> allowed.contains(a.getAuthority().replaceFirst("^ROLE_", ""))))
            throw new ApiException(403, "Table operation forbidden");
    }
    public Object owner(SchemaCatalog.Table table, Authentication auth) {
        var p = policy(table);
        if (p == null || p.rowColumn() == null || p.rowColumn().isBlank()) return null;
        table.column(p.rowColumn());
        String claim = p.rowClaim() == null || p.rowClaim().isBlank() ? "sub" : p.rowClaim();
        Object value = auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaims().get(claim)
                : "sub".equals(claim) ? auth.getName() : null;
        if (!(value instanceof String || value instanceof Number)) throw new ApiException(403, "Required row ownership claim missing");
        return Values.convert(table.column(p.rowColumn()), value);
    }
    public String ownerColumn(SchemaCatalog.Table table) { return policy(table).rowColumn(); }
}
