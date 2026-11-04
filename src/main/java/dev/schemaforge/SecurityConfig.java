package dev.schemaforge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import io.github.bucket4j.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean
    JwtDecoder decoder(ForgeProperties config) {
        var s = config.security();
        if (s.jwtSecret() == null || s.jwtSecret().getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes; use a cryptographically random secret");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(s.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(s.audience()) && jwt.getExpiresAt() != null
                && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(s.issuer()), audience));
        return decoder;
    }
    @Bean
    SecurityFilterChain security(HttpSecurity http, ForgeProperties config) throws Exception {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles"); roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**", "/docs.html", "/docs.js", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .requestMatchers("/actuator/prometheus").hasRole("ADMIN").anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)))
            .addFilterBefore(new ApiKeyFilter(config), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new RateFilter(config.requestsPerMinute()), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
    static final class ApiKeyFilter extends OncePerRequestFilter {
        private final List<ForgeProperties.ApiKey> keys;
        ApiKeyFilter(ForgeProperties config) {
            keys = config.security().apiKeys() == null ? List.of() : config.security().apiKeys();
            for (var k : keys) {
                if (k.sha256() == null || !k.sha256().matches("[a-fA-F0-9]{64}") || k.subject() == null || k.subject().isBlank() || k.roles() == null)
                    throw new IllegalStateException("API keys require a SHA-256 hex digest, subject, and roles");
            }
        }
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            String key = req.getHeader("X-API-KEY");
            if (key != null) {
                if (req.getHeader("Authorization") != null || key.length() > 512) { res.sendError(401); return; }
                byte[] digest;
                try { digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)); }
                catch (Exception e) { throw new IllegalStateException(e); }
                ForgeProperties.ApiKey matched = null;
                for (var entry : keys) if (MessageDigest.isEqual(digest, HexFormat.of().parseHex(entry.sha256()))) matched = entry;
                if (matched == null) { res.sendError(401); return; }
                var auth = UsernamePasswordAuthenticationToken.authenticated(matched.subject(), null,
                    matched.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(req, res);
        }
    }
    static final class RateFilter extends OncePerRequestFilter {
        private final int capacity;
        private final Map<String, Bucket> buckets = new LinkedHashMap<>(128, .75f, true);
        RateFilter(int capacity) { if (capacity < 1) throw new IllegalStateException("requests-per-minute must be positive"); this.capacity = capacity; }
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && (req.getRequestURI().startsWith("/api/") || req.getRequestURI().equals("/openapi.json"))) {
                Bucket bucket;
                synchronized (buckets) {
                    bucket = buckets.computeIfAbsent(auth.getName(), k -> Bucket.builder()
                        .addLimit(Bandwidth.builder().capacity(capacity).refillGreedy(capacity, Duration.ofMinutes(1)).build()).build());
                    if (buckets.size() > 10000) buckets.remove(buckets.keySet().iterator().next());
                }
                if (!bucket.tryConsume(1)) { res.setHeader("Retry-After", "60"); res.sendError(429); return; }
            }
            chain.doFilter(req, res);
        }
    }
}
