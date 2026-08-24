package com.anas.ledgerwallet.common.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.anas.ledgerwallet.IntegrationTestBase;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end check that the application boots and serves traffic.
 *
 * <p>Covers what the web-layer test cannot: the Spring context starts with the real
 * datasource, Flyway applies its migrations, Hibernate validates the schema, and the
 * HTTP stack answers over a real socket.
 */
class HealthEndpointIT extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Application boots and GET /health returns UP over HTTP")
    void healthEndpointRespondsOverHttp() {
        ResponseEntity<HealthResponse> response =
                restTemplate.getForEntity("/health", HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("UP");
    }

    @Test
    @DisplayName("Health endpoint is reachable without credentials")
    void healthEndpointIsPublic() {
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Unlisted paths are protected by default")
    void otherPathsRequireAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity("/accounts", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Flyway applied the baseline migration")
    void flywayBaselineApplied() {
        Integer applied = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true", Integer.class);

        assertThat(applied).isNotNull().isPositive();
    }
}
