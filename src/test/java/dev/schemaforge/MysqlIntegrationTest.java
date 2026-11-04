package dev.schemaforge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="FORGE_MYSQL_TESTS", matches="true")
@SpringBootTest(properties={
    "spring.datasource.url=${FORGE_MYSQL_URL}", "spring.datasource.username=schemaforge", "spring.datasource.password=${DB_PASSWORD}",
    "spring.sql.init.mode=never", "forge.schema=schemaforge", "forge.cache-enabled=false",
    "forge.security.jwt-secret=testing-only-secret-at-least-32-bytes-long"
})
@AutoConfigureMockMvc
class MysqlIntegrationTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Test void generatedKeysJsonAndMysqlPagination() throws Exception {
        String token = new ApiIntegrationTest().admin();
        String prefix = "mysql-"+UUID.randomUUID();
        List<Long> ids = new ArrayList<>();
        try {
            for (int i=0; i<2; i++) {
                var result = http.perform(post("/api/v1/products").header("Authorization",token).contentType("application/json")
                    .content(json.writeValueAsString(Map.of("name",prefix+i,"price",1.25,"metadata",Map.of("source","mysql")))))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber()).andReturn();
                ids.add(json.readTree(result.getResponse().getContentAsString()).get("id").asLong());
            }
            http.perform(get("/api/v1/products").param("name[like]",prefix+"%").param("limit","1").param("page","2").param("sort","id").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value(prefix+"1")).andExpect(jsonPath("$.data[0].metadata.source").value("mysql"));
            var first = http.perform(get("/api/v1/products").param("name[like]",prefix+"%").param("cursor","first").param("limit","1").param("select","name").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value(prefix+"0")).andExpect(jsonPath("$.data[0].id").doesNotExist()).andReturn();
            String next = json.readTree(first.getResponse().getContentAsString()).get("nextCursor").asText();
            http.perform(get("/api/v1/products").param("name[like]",prefix+"%").param("cursor",next).param("limit","1").header("Authorization",token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value(prefix+"1"));
            http.perform(patch("/api/v1/products/"+ids.get(0)).header("Authorization",token).contentType("application/json").content("{\"price\":3.75}"))
                .andExpect(status().isNoContent());
            http.perform(get("/api/v1/products/"+ids.get(0)).header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.price").value(3.75));
        } finally {
            for (long id : ids) http.perform(delete("/api/v1/products/"+id).header("Authorization",token)).andExpect(status().isNoContent());
        }
    }
}
