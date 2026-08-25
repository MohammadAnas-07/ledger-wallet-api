package com.anas.ledgerwallet.auth.dto;

/**
 * Issued token.
 *
 * <p>{@code expiresInSeconds} is the token lifetime, not an absolute timestamp, so a
 * client with a skewed clock can still refresh before expiry.
 */
public record AuthResponse(String accessToken, String tokenType, long expiresInSeconds) {

    public static AuthResponse bearer(String accessToken, long expiresInSeconds) {
        return new AuthResponse(accessToken, "Bearer", expiresInSeconds);
    }
}
