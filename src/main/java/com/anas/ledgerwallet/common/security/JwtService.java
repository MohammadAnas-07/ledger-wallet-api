package com.anas.ledgerwallet.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Issues and validates JWTs.
 *
 * <p>Tokens are signed with HS256 and carry the user id as the subject. They are
 * short-lived and never revocable on their own — nothing here consults a database, so
 * a token stays valid until it expires. That is the reason the lifetime is measured in
 * minutes rather than days.
 */
@Service
public class JwtService {

    /** HS256 requires a key of at least 256 bits; shorter keys weaken the signature. */
    private static final int MIN_SECRET_BYTES = 32;

    private static final String CLAIM_EMAIL = "email";

    private final SecretKey signingKey;
    private final Duration tokenLifetime;

    public JwtService(
            @Value("${security.jwt.secret}") String secret,
            @Value("${security.jwt.expiration-minutes}") long expirationMinutes) {

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            // Fail at startup rather than issuing weakly-signed tokens. A misconfigured
            // secret that only surfaces at the first login is a much worse outcome.
            throw new IllegalStateException(
                    "security.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " bytes for HS256; got " + keyBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.tokenLifetime = Duration.ofMinutes(expirationMinutes);
    }

    public String generateToken(UUID userId, String email) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(tokenLifetime);

        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_EMAIL, email)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Returns the user id carried by a valid token, or empty if the token is expired,
     * tampered with, malformed, or signed with a different key.
     *
     * <p>Returns an {@link Optional} rather than throwing because for the filter every
     * one of those cases has the same outcome — no authentication — and distinguishing
     * them in a response would tell an attacker which part of the token to fix.
     */
    public Optional<UUID> extractUserId(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    // Verifies the signature and rejects an expired token. Both are
                    // checked here, so no caller has to remember to check expiry.
                    .parseSignedClaims(token)
                    .getPayload();

            return Optional.of(UUID.fromString(claims.getSubject()));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long getTokenLifetimeSeconds() {
        return tokenLifetime.toSeconds();
    }
}
