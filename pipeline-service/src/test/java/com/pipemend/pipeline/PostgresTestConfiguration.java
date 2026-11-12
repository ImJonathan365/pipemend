package com.pipemend.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real PostgreSQL for integration tests (NFR-16), plus a temporary inbox per Spring context and an ai-service that
 * refuses every connection.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    public static final String AI_DOWN = "http://127.0.0.1:1";

    // Shared by every context, e.g. the one with a real HTTP server, so the suite still starts one container.
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    @Bean
    DynamicPropertyRegistrar temporaryInbox() throws IOException {
        Path inbox = Files.createTempDirectory("pipemend-inbox");
        return registry -> {
            registry.add("pipemend.inbox-dir", inbox::toString);
            // A closed port on loopback: no test reaches ai-service or the network, and every call is refused at once.
            registry.add("pipemend.ai.base-url", () -> AI_DOWN);
        };
    }
}
