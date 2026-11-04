package dev.schemaforge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:forge;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.sql.init.mode=always",
    "forge.schema=public", "forge.security.jwt-secret=testing-only-secret-at-least-32-bytes-long",
    "forge.requests-per-minute=10000", "forge.policies.users.read[0]=ADMIN", "forge.policies.users.read[1]=GUEST",
    "forge.policies.users.write[0]=ADMIN", "forge.policies.orders.read[0]=ADMIN", "forge.policies.orders.write[0]=ADMIN",
    "forge.policies.orders.row-column=user_id", "forge.policies.orders.row-claim=sub",
    "forge.security.api-keys[0].sha256=f56c37abb354d5b7583c96750836158398e2c29369c4fa1a27dd807f8dce1e5e",
    "forge.security.api-keys[0].subject=1", "forge.security.api-keys[0].roles[0]=GUEST"
})
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@AutoConfigureMockMvc
class ApiIntegrationTest {
    static final String SECRET = "testing-only-secret-at-least-32-bytes-long";
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @BeforeEach void seed() {
        jdbc.update("DELETE FROM orders"); jdbc.update("DELETE FROM products"); jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM memberships");
        jdbc.update("ALTER TABLE users ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE products ALTER COLUMN id RESTART WITH 1");
        jdbc.update("ALTER TABLE orders ALTER COLUMN id RESTART WITH 1");
        jdbc.update("INSERT INTO users(name,email,age) VALUES ('Ada','ada@test',28),('Lin','lin@test',32),('Eve','eve@test',19)");
        jdbc.update("INSERT INTO products(name,price) VALUES ('Notebook',12.50),('Pen',2.25)");
        jdbc.update("INSERT INTO orders(user_id,product_id,quantity) VALUES (1,1,2),(2,2,3)");
    }
    String token(String sub, String role) throws Exception { return token(sub,role,"schemaforge","schemaforge-api",Instant.now().plusSeconds(600),SECRET); }
    String token(String sub, String role, String issuer, String audience, Instant expires, String secret) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(sub).issuer(issuer).audience(audience).expirationTime(Date.from(expires))
            .issueTime(Date.from(Instant.now())).claim("roles",List.of(role)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8))); return "Bearer " + jwt.serialize();
    }
    String admin() throws Exception { return token("1","ADMIN"); }
    @Test void authenticationIsMandatory() throws Exception {
        http.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
        http.perform(get("/openapi.json")).andExpect(status().isUnauthorized());
        http.perform(get("/actuator/health")).andExpect(status().isOk());
    }
    @Test void jwtSignatureIssuerAudienceAndExpiryAreVerified() throws Exception {
        for (String value : List.of(
            token("1","ADMIN","bad","schemaforge-api",Instant.now().plusSeconds(60),SECRET),
            token("1","ADMIN","schemaforge","bad",Instant.now().plusSeconds(60),SECRET),
            token("1","ADMIN","schemaforge","schemaforge-api",Instant.now().minusSeconds(300),SECRET),
            token("1","ADMIN","schemaforge","schemaforge-api",Instant.now().plusSeconds(60),"another-secret-of-at-least-32-bytes-long")))
            http.perform(get("/api/v1/users").header("Authorization",value)).andExpect(status().isUnauthorized());
    }
    @Test void unknownRoleIsDenied() throws Exception {
        http.perform(get("/api/v1/users").header("Authorization",token("1","UNKNOWN"))).andExpect(status().isForbidden());
    }
    @Test void roleCanReadButCannotWrite() throws Exception {
        String guest = token("1","GUEST");
        http.perform(get("/api/v1/users").header("Authorization",guest)).andExpect(status().isOk());
        http.perform(delete("/api/v1/users/3").header("Authorization",guest)).andExpect(status().isForbidden());
        http.perform(get("/api/v1/products").header("Authorization",guest)).andExpect(status().isForbidden());
    }
    @Test void filtersSortProjectionAndPagination() throws Exception {
        http.perform(get("/api/v1/users").param("age[gt]","20").param("sort","-age").param("select","id,name").param("limit","1")
            .header("Authorization",admin())).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("Lin"))
            .andExpect(jsonPath("$.data[0].email").doesNotExist()).andExpect(jsonPath("$.hasMore").value(true));
        http.perform(get("/api/v1/users").param("age>20","").param("limit","1").param("page","2").header("Authorization",admin()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("Lin"));
    }
    @Test void likeAndNullFilters() throws Exception {
        http.perform(get("/api/v1/users").param("name[like]","A%").header("Authorization",admin()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        http.perform(get("/api/v1/products").param("external_id[isnull]","true").header("Authorization",admin()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
    }
    @Test void cursorPaginationPreservesProjection() throws Exception {
        var response = http.perform(get("/api/v1/users").param("cursor","first").param("limit","1").param("select","name").header("Authorization",admin()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").doesNotExist()).andReturn();
        String next = json.readTree(response.getResponse().getContentAsString()).get("nextCursor").asText();
        http.perform(get("/api/v1/users").param("cursor",next).param("limit","1").header("Authorization",admin()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("Lin"));
    }
    @Test void rejectsMalformedQueryAndIdentifiers() throws Exception {
        for (String column : List.of("name;DROP TABLE users", "age[nope]", "missing"))
            http.perform(get("/api/v1/users").param(column,"1").header("Authorization",admin())).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/users").param("sort","id DESC;DROP TABLE users").header("Authorization",admin())).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/users").param("limit","201").header("Authorization",admin())).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/users").param("page","0").header("Authorization",admin())).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/users").param("age","1 OR 1=1").header("Authorization",admin())).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/users").param("cursor","first").param("sort","id").header("Authorization",admin())).andExpect(status().isBadRequest());
    }
    @Test void stringInjectionIsOnlyData() throws Exception {
        http.perform(get("/api/v1/users").param("name","Ada' OR '1'='1").header("Authorization",admin()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class)).isEqualTo(3);
    }
    @Test void completeCrudLifecycle() throws Exception {
        String value = http.perform(post("/api/v1/users").header("Authorization",admin()).contentType("application/json")
            .content("{\"name\":\"New\",\"email\":\"new@test\",\"age\":20}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = json.readTree(value).get("id").asLong();
        http.perform(get("/api/v1/users/"+id).header("Authorization",admin())).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("New"));
        http.perform(patch("/api/v1/users/"+id).header("Authorization",admin()).contentType("application/json").content("{\"age\":21}"))
            .andExpect(status().isNoContent());
        http.perform(put("/api/v1/users/"+id).header("Authorization",admin()).contentType("application/json")
            .content("{\"name\":\"Replaced\",\"email\":\"new@test\",\"age\":22,\"created_at\":\"2026-01-01T12:00:00\"}"))
            .andExpect(status().isNoContent());
        http.perform(delete("/api/v1/users/"+id).header("Authorization",admin())).andExpect(status().isNoContent());
        http.perform(get("/api/v1/users/"+id).header("Authorization",admin())).andExpect(status().isNotFound());
    }
    @Test void putRequiresCompleteRepresentationAndKeysAreImmutable() throws Exception {
        http.perform(put("/api/v1/users/1").header("Authorization",admin()).contentType("application/json").content("{\"name\":\"X\"}"))
            .andExpect(status().isBadRequest());
        http.perform(patch("/api/v1/users/1").header("Authorization",admin()).contentType("application/json").content("{\"id\":2}"))
            .andExpect(status().isBadRequest());
    }
    @Test void constraintsAndInvalidTypesDoNotLeakSql() throws Exception {
        http.perform(post("/api/v1/users").header("Authorization",admin()).contentType("application/json").content("{\"name\":\"X\",\"email\":\"ada@test\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Database constraint violation"));
        http.perform(patch("/api/v1/users/1").header("Authorization",admin()).contentType("application/json").content("{\"age\":1.5}"))
            .andExpect(status().isBadRequest());
    }
    @Test void rowSecurityAppliesToReadUpdateDeleteAndCreate() throws Exception {
        http.perform(get("/api/v1/orders").header("Authorization",admin())).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        http.perform(get("/api/v1/orders/2").header("Authorization",admin())).andExpect(status().isNotFound());
        http.perform(delete("/api/v1/orders/2").header("Authorization",admin())).andExpect(status().isNotFound());
        http.perform(patch("/api/v1/orders/2").header("Authorization",admin()).contentType("application/json").content("{\"quantity\":9}"))
            .andExpect(status().isNotFound());
        http.perform(patch("/api/v1/orders/1").header("Authorization",admin()).contentType("application/json").content("{\"user_id\":2}"))
            .andExpect(status().isForbidden());
        http.perform(post("/api/v1/orders").header("Authorization",admin()).contentType("application/json").content("{\"user_id\":2,\"product_id\":1,\"quantity\":1}"))
            .andExpect(status().isForbidden());
        http.perform(post("/api/v1/orders").header("Authorization",admin()).contentType("application/json").content("{\"product_id\":1,\"quantity\":1}"))
            .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT user_id FROM orders WHERE id=3",Long.class)).isEqualTo(1);
    }
    @Test void relatedDataAlsoEnforcesChildRowSecurity() throws Exception {
        http.perform(get("/api/v1/users/1/orders").header("Authorization",admin())).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1));
        http.perform(get("/api/v1/users/2/orders").header("Authorization",admin())).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }
    @Test void uuidBlobAndTimestampMapping() throws Exception {
        http.perform(patch("/api/v1/products/1").header("Authorization",admin()).contentType("application/json")
            .content("{\"external_id\":\"550e8400-e29b-41d4-a716-446655440000\",\"payload\":\"aGVsbG8=\"}"))
            .andExpect(status().isNoContent());
        http.perform(get("/api/v1/products/1").header("Authorization",admin())).andExpect(status().isOk())
            .andExpect(jsonPath("$.external_id").value("550e8400-e29b-41d4-a716-446655440000")).andExpect(jsonPath("$.payload").value("aGVsbG8="));
        http.perform(get("/api/v1/users/1").header("Authorization",admin())).andExpect(jsonPath("$.created_at").isString());
    }
    @Test void openApiIsDerivedAndRoleScoped() throws Exception {
        http.perform(get("/openapi.json").header("Authorization",admin())).andExpect(status().isOk())
            .andExpect(jsonPath("$.openapi").value("3.0.3")).andExpect(jsonPath("$.paths['/api/v1/users'].get").exists())
            .andExpect(jsonPath("$.paths['/api/v1/users/{id}'].patch").exists()).andExpect(jsonPath("$.components.schemas.users.properties.age.type").value("integer"));
        http.perform(get("/openapi.json").header("Authorization",token("1","GUEST"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/users'].post").doesNotExist()).andExpect(jsonPath("$.paths['/api/v1/products']").doesNotExist());
    }
    @Test void compositeTableInsertsReturnProvidedKeys() throws Exception {
        http.perform(post("/api/v1/memberships").header("Authorization",admin()).contentType("application/json")
            .content("{\"user_id\":1,\"team_id\":2}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.user_id").value(1)).andExpect(jsonPath("$.team_id").value(2));
    }
    @Test void compositeKeysHaveExplicitItemLimitation() throws Exception {
        http.perform(get("/api/v1/memberships/1").header("Authorization",admin())).andExpect(status().isBadRequest());
        http.perform(get("/api/v1/memberships").header("Authorization",admin())).andExpect(status().isOk());
    }
    @Test void machineApiKeyAuthentication() throws Exception {
        http.perform(get("/api/v1/users").header("X-API-KEY","test-machine-key")).andExpect(status().isOk());
        http.perform(delete("/api/v1/users/3").header("X-API-KEY","test-machine-key")).andExpect(status().isForbidden());
        http.perform(get("/api/v1/users").header("X-API-KEY","wrong-key")).andExpect(status().isUnauthorized());
        http.perform(get("/api/v1/users").header("X-API-KEY","test-machine-key").header("Authorization",admin())).andExpect(status().isUnauthorized());
    }
    @Test void metricsRequireAdministrator() throws Exception {
        http.perform(get("/actuator/prometheus").header("Authorization",token("1","GUEST"))).andExpect(status().isForbidden());
        http.perform(get("/actuator/prometheus").header("Authorization",admin())).andExpect(status().isOk());
    }
}
