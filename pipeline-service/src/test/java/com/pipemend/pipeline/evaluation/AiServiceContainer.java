package com.pipemend.pipeline.evaluation;

import com.github.dockerjava.api.exception.NotFoundException;
import com.pipemend.pipeline.persistence.AiCallLogRepository;
import com.pipemend.pipeline.triage.AiClientProperties;
import com.pipemend.pipeline.triage.AiTriagePort;
import com.pipemend.pipeline.triage.HttpAiTriageClient;
import java.net.URI;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real ai-service with the mock provider, from the image CI builds before the tests (AC-20.3). The image is never
 * pulled, so no test reaches a registry, and a missing image fails the test instead of skipping it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AiServiceContainer {

    public static final String IMAGE = "pipemend/ai-service:test";
    private static final int PORT = 8000;

    @Bean(destroyMethod = "stop")
    GenericContainer<?> aiService() {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(IMAGE).exec();
        } catch (NotFoundException e) {
            throw new IllegalStateException(
                    "AC-20.3 needs the image " + IMAGE + ". Build it with: docker build -t " + IMAGE + " ai-service",
                    e);
        }
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withImagePullPolicy(image -> false)
                // Only the provider: every other setting keeps the ai-service default, as in docker-compose.yml.
                .withEnv("LLM_PROVIDER", "mock")
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHttp("/health").forStatusCode(200));
        container.start();
        return container;
    }

    /** Same reason as IngestionJobTriageTest.WireMockClient: the closed port of PostgresTestConfiguration wins. */
    @Bean
    @Primary
    AiTriagePort aiServiceTriage(
            GenericContainer<?> aiService, AiClientProperties defaults, JsonMapper json, AiCallLogRepository callLog) {
        return new HttpAiTriageClient(
                new AiClientProperties(
                        URI.create("http://127.0.0.1:" + aiService.getMappedPort(PORT)),
                        defaults.connectTimeout(),
                        defaults.readTimeout()),
                json,
                callLog);
    }
}
