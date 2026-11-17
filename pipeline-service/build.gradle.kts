plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.diffplug.spotless") version "8.10.3"
}

group = "com.pipemend"
version = "0.0.1-SNAPSHOT"
description = "PipeMend pipeline-service - ingestion, validation, correction policy and persistence"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// Not managed by the Boot BOM; 3.1.1 is built against Spring Boot 4.1.
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.yaml:snakeyaml")
	// Core modules only (NFR-07): the Spring Boot starter would bring AOP and configuration this client does not use.
	implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.4.0")
	implementation("io.github.resilience4j:resilience4j-retry:2.4.0")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.awaitility:awaitility")
	// Not managed by the Boot BOM; the standalone jar shades its own Jetty and Jackson 2 (NFR-16).
	testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    // AC-20.3 runs against the ai-service image, so an ai-service change must rerun the tests instead of
    // reusing an up-to-date result. The image itself is still rebuilt by hand (AGENTS.md).
    inputs.files(
        fileTree("../ai-service/app") { exclude("**/__pycache__/**") },
        "../ai-service/Dockerfile",
        "../ai-service/pyproject.toml",
        "../ai-service/uv.lock",
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("aiServiceSources")
}

spotless {
    java {
        palantirJavaFormat("2.100.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}
