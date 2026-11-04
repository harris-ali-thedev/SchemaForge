package dev.schemaforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ForgeProperties.class)
public class SchemaForgeApplication {
    public static void main(String[] args) { SpringApplication.run(SchemaForgeApplication.class, args); }
}
