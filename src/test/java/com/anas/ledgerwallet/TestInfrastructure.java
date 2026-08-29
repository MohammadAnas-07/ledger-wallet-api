package com.anas.ledgerwallet;

import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The containers every integration test runs against, and the properties that point
 * the application at them.
 *
 * <p>Separate from {@link IntegrationTestBase} so a test class can reuse the same
 * containers while registering different application settings. Inheriting the base
 * class would not allow that: its {@code @DynamicPropertySource} outranks
 * {@code @TestPropertySource}, so a subclass cannot lower a value the base has set —
 * which is exactly what a rate-limit test needs to do.
 *
 * <p>Started once per test JVM and stopped by Testcontainers at exit, so adding a
 * second context costs a context start, not a container start.
 */
public final class TestInfrastructure {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ledger_wallet_test")
                    .withUsername("test")
                    .withPassword("test")
                    // The default is 60s, and first startup on a cold Docker Desktop has
                    // been observed at ~45s. Too close to the limit to leave alone: a
                    // timeout here would surface as an intermittent failure, which
                    // rules.md 3.2 says must never be written off as a flaky test.
                    .withStartupTimeout(Duration.ofMinutes(3));

    /**
     * A real broker, matching the image compose runs.
     *
     * <p>Not an embedded or mocked one: the point of these tests is that a committed
     * transaction reaches a topic and a consumer reads it back, which is exactly the
     * part a fake would assume rather than prove.
     */
    private static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
                    .withStartupTimeout(Duration.ofMinutes(3));

    /**
     * A fixed test signing key, long enough to satisfy the HS256 minimum that
     * {@code JwtService} enforces at startup. Test-only and committed on purpose —
     * it signs nothing outside this suite, and the real key comes from the
     * environment with no fallback (rules.md 2.5).
     */
    private static final String TEST_JWT_SECRET =
            "integration-test-signing-key-not-used-anywhere-else";

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    private TestInfrastructure() {
    }

    /** Points the datasource, JWT settings and Kafka client at the live containers. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("JWT_SECRET", () -> TEST_JWT_SECRET);
        registry.add("JWT_EXPIRATION_MINUTES", () -> 15);
        registry.add("KAFKA_BOOTSTRAP_SERVERS", KAFKA::getBootstrapServers);
    }
}
