package com.anas.ledgerwallet.common.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anas.ledgerwallet.auth.UserRepository;
import com.anas.ledgerwallet.common.config.SecurityConfig;
import com.anas.ledgerwallet.common.ratelimit.AuthRateLimitFilter;
import com.anas.ledgerwallet.common.security.JwtAuthenticationFilter;
import com.anas.ledgerwallet.common.security.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer tests for the health endpoint.
 *
 * <p>These run without a database or a container, so they stay fast. The full
 * application boot is covered separately by {@code HealthEndpointIT}.
 *
 * <p>The chain's own filters are imported alongside it: the configuration wires
 * them in by type, so a missing one is a context failure rather than a silently
 * shorter chain.
 *
 * <p>The real filter chain is imported rather than disabled — these tests exist to
 * assert what the chain does, so replacing it with a permissive stand-in would leave
 * them asserting nothing. Its collaborators are mocked instead.
 */
@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthRateLimitFilter.class})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserDetailsService userDetailsService;

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

    @Test
    @DisplayName("The profile endpoint is not public, unlike register and login")
    void profileEndpointIsProtected() throws Exception {
        // /api/v1/auth/me sits under the same prefix as the two public auth paths.
        // A /api/v1/auth/** wildcard in the chain would quietly expose it.
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
