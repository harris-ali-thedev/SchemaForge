package dev.schemaforge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Opt-in real-service smoke test; requires docker/init.sql loaded in PostgreSQL. */
@EnabledIfEnvironmentVariable(named="FORGE_SERVICE_TESTS", matches="true")
@SpringBootTest(properties={
    "spring.datasource.url=${FORGE_PG_URL}", "spring.datasource.username=schemaforge", "spring.datasource.password=${DB_PASSWORD}",
    "spring.sql.init.mode=never", "forge.schema=public", "forge.cache-enabled=true",
    "spring.data.redis.port=${FORGE_REDIS_PORT:6379}",
    "forge.security.jwt-secret=testing-only-secret-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class PostgresRedisIntegrationTest {
    @Autowired MockMvc http;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Test void postgresJsonbCrudAndRedisInvalidation() throws Exception {
        String token = new ApiIntegrationTest().admin();
        String name = "service-test-" + java.util.UUID.randomUUID();
        var created = http.perform(post("/api/v1/products").header("Authorization",token).contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of("name",name,"price",5.25,"metadata",java.util.Map.of("color","blue")))))
            .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        try {
            for (int i=0; i<2; i++) http.perform(get("/api/v1/products").param("name",name).header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].metadata.color").value("blue"));
            try (var connection = redis.getConnectionFactory().getConnection()) {
                assertThat(connection.serverCommands().dbSize()).isGreaterThan(0);
            }
            http.perform(patch("/api/v1/products/"+id).header("Authorization",token).contentType("application/json")
                .content("{\"metadata\":{\"color\":\"green\"}}"))
                .andExpect(status().isNoContent());
            http.perform(get("/api/v1/products").param("name",name).header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].metadata.color").value("green"));
            assertThat(redis.opsForValue().get("forge:epoch:products")).isNotNull();
        } finally {
            http.perform(delete("/api/v1/products/"+id).header("Authorization",token)).andExpect(status().isNoContent());
        }
    }
}
