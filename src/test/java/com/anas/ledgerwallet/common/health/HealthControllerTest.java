package com.anas.ledgerwallet.common.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anas.ledgerwallet.common.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer tests for the health endpoint.
 *
 * <p>These run without a database or a container, so they stay fast. The full
 * application boot is covered separately by {@code HealthEndpointIT}.
 */
@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /health returns 200 with status UP")
    void healthReturnsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("GET /health is reachable without authentication")
    void healthIsPublic() throws Exception {
        // No credentials supplied: the endpoint must not return 401.
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Any other path requires authentication (deny by default)")
    void unknownPathRequiresAuthentication() throws Exception {
        // Guards rules.md 2.1: the filter chain denies by default, so a path nobody
        // explicitly made public is protected. If this ever returns 200, the chain
        // has been loosened.
        mockMvc.perform(get("/some/other/path"))
                .andExpect(status().isUnauthorized());
    }
}
