package com.documind;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LocalFoundationIT {
    private static PostgreSQLContainer container;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired Flyway flyway;
    @Autowired ApplicationContext context;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        Properties local = new Properties();
        Path env = Path.of(".env");
        if (Files.isRegularFile(env)) {
            try (Reader reader = Files.newBufferedReader(env, StandardCharsets.UTF_8)) {
                local.load(reader);
            }
        }
        String url = setting(local, "TEST_DB_URL", null);
        if (Boolean.getBoolean("documind.testcontainers")) url = null;
        String username;
        String password;
        String migrationUsername;
        String migrationPassword;
        if (url == null || url.isBlank()) {
            container = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg15")
                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("documind_phase1_test")
                    .withUsername("documind_test")
                    .withPassword(UUID.randomUUID().toString());
            container.start();
            url = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
            migrationUsername = username;
            migrationPassword = password;
        } else {
            // Never run integration migrations against the development or another project's DB.
            if (!url.matches("jdbc:postgresql://[^/]+/documind_phase1_test(?:\\?.*)?")) {
                throw new IllegalStateException("TEST_DB_URL must target documind_phase1_test");
            }
            username = required(local, "TEST_DB_USERNAME");
            password = required(local, "TEST_DB_PASSWORD");
            migrationUsername = setting(local, "TEST_DB_MIGRATION_USERNAME", username);
            migrationPassword = setting(local, "TEST_DB_MIGRATION_PASSWORD", password);
        }
        String resolvedUrl = url;
        String resolvedUsername = username;
        String resolvedPassword = password;
        String resolvedMigrationUsername = migrationUsername;
        String resolvedMigrationPassword = migrationPassword;
        registry.add("spring.datasource.url", () -> resolvedUrl);
        registry.add("spring.datasource.username", () -> resolvedUsername);
        registry.add("spring.datasource.password", () -> resolvedPassword);
        registry.add("spring.flyway.user", () -> resolvedMigrationUsername);
        registry.add("spring.flyway.password", () -> resolvedMigrationPassword);
    }

    private static String setting(Properties local, String key, String fallback) {
        return System.getenv().getOrDefault(key, local.getProperty(key, fallback));
    }

    private static String required(Properties local, String key) {
        String value = setting(local, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing test database setting: " + key);
        }
        return value;
    }

    @AfterAll
    static void closeContainer() {
        if (container != null) container.stop();
    }

    @Test
    void contextMigratesPostgresAndSupportsVectorOperationsWithoutAiCredentials() {
        assertThat(jdbc.sql("SELECT extversion FROM pg_extension WHERE extname='vector'")
                .query(String.class).single()).isEqualTo("0.8.7");
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("1");
        assertThat(jdbc.sql("SELECT '[1,2,3]'::vector <-> '[1,2,3]'::vector")
                .query(Double.class).single()).isZero();
        assertThat(context.getBeansOfType(ChatModel.class)).isEmpty();
        assertThat(context.getBeansOfType(EmbeddingModel.class)).isEmpty();
    }

    @Test
    void healthReadinessAndSwaggerAreAccessibleWithoutCredentials() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("DocuMind Backend"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void unimplementedBusinessRoutesAreClosedWithCorrelatedProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/documents").header("X-Request-ID", "phase1-security"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-ID", "phase1-security"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value("phase1-security"));
    }
}
