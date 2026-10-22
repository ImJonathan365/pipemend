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
 * Real PostgreSQL for integration tests (NFR-16), plus a temporary inbox. Every test class that imports only this
 * configuration gets the same cached Spring context, and therefore the same container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18-alpine");
    }

    @Bean
    DynamicPropertyRegistrar temporaryInbox() throws IOException {
        Path inbox = Files.createTempDirectory("pipemend-inbox");
        return registry -> registry.add("pipemend.inbox-dir", inbox::toString);
    }
}
