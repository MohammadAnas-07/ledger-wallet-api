package com.anas.ledgerwallet.auth.dto;

import com.anas.ledgerwallet.auth.User;
import java.time.Instant;
import java.util.UUID;

/**
 * Public view of a user. Carries no credential material — the mapping below is the
 * single place that decides what leaves the service, and {@code passwordHash} is not
 * on it.
 */
public record UserResponse(UUID id, String email, String fullName, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(), user.getEmail(), user.getFullName(), user.getCreatedAt());
    }
}
