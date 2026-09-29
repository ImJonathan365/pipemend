package com.pipemend.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Sprint 1 smoke test: the Spring context must wire up correctly.
 *
 * <p>Flyway is off and the datasource points nowhere. HikariCP connects lazily, so the context
 * loads without PostgreSQL. This proves wiring only, not database behaviour: real integration
 * tests arrive in Sprint 2 with Testcontainers (NFR-16), together with the first migration.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "spring.flyway.enabled=false",
            "spring.datasource.url=jdbc:postgresql://localhost:1/unused",
            "spring.datasource.username=unused",
            "spring.datasource.password=unused"
        })
class PipelineServiceApplicationTests {

    @Test
    void contextLoads() {
        // Fails if any bean cannot be created.
    }
}
