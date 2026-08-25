package com.anas.ledgerwallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256-signing";
    private static final String OTHER_SECRET = "a-completely-different-secret-of-sufficient-length";

    private final JwtService jwtService = new JwtService(SECRET, 15);

    @Test
    @DisplayName("A generated token carries back the user id it was issued for")
    void roundTripsUserId() {
        UUID userId = UUID.randomUUID();

        String token = jwtService.generateToken(userId, "user@example.com");

        assertThat(jwtService.extractUserId(token)).contains(userId);
    }

    @Test
    @DisplayName("A token signed with a different secret is rejected")
    void rejectsForeignSignature() {
        String foreignToken = new JwtService(OTHER_SECRET, 15)
                .generateToken(UUID.randomUUID(), "user@example.com");

        // The token is well-formed and unexpired; only the signature differs. Without
        // verification this would authenticate anyone able to mint their own token.
        assertThat(jwtService.extractUserId(foreignToken)).isEmpty();
    }

    @Test
    @DisplayName("An expired token is rejected")
    void rejectsExpiredToken() {
        // Negative lifetime: issued already expired, so no waiting in the test.
        JwtService expiringService = new JwtService(SECRET, -1);
        String expired = expiringService.generateToken(UUID.randomUUID(), "user@example.com");

        assertThat(jwtService.extractUserId(expired)).isEmpty();
    }

    @Test
    @DisplayName("A tampered token is rejected")
    void rejectsTamperedToken() {
        String token = jwtService.generateToken(UUID.randomUUID(), "user@example.com");
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThat(jwtService.extractUserId(tampered)).isEmpty();
    }

    @Test
    @DisplayName("Garbage input is rejected rather than throwing")
    void rejectsMalformedToken() {
        // The filter passes whatever arrived in the header straight through, so this
        // must degrade to "not authenticated" and never to a 500.
        assertThat(jwtService.extractUserId("not-a-jwt")).isEmpty();
        assertThat(jwtService.extractUserId("")).isEmpty();
    }

    @Test
    @DisplayName("A secret shorter than 256 bits fails at construction")
    void rejectsWeakSecret() {
        assertThatThrownBy(() -> new JwtService("too-short", 15))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("Reported lifetime matches the configured expiry")
    void reportsLifetime() {
        assertThat(new JwtService(SECRET, 15).getTokenLifetimeSeconds()).isEqualTo(900);
    }

    @Test
    @DisplayName("Two tokens for different users do not resolve to the same id")
    void tokensAreUserSpecific() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        Optional<UUID> resolved =
                jwtService.extractUserId(jwtService.generateToken(second, "b@example.com"));

        assertThat(resolved).contains(second);
        assertThat(resolved).isNotEqualTo(Optional.of(first));
    }
}
