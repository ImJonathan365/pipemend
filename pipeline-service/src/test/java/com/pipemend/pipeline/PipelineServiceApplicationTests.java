package com.pipemend.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Smoke test: the Spring context must wire up correctly.
 *
 * <p>Runs against a real PostgreSQL because the JDBC job repository of Spring Batch reads the database type from a
 * live connection while the context starts.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PipelineServiceApplicationTests {

    @Test
    void contextLoads() {
        // Fails if any bean cannot be created.
    }
}
