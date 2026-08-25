package com.anas.ledgerwallet.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration payload. Validated at the boundary so the service is never the first
 * place a null or malformed value is noticed (rules.md 2.3).
 */
public record RegisterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid address")
        @Size(max = 320, message = "Email must be at most 320 characters")
        String email,

        // Length is the only strength rule enforced here. Composition rules
        // ("one uppercase, one symbol") push users toward predictable patterns
        // without adding much real entropy, so length does the work instead.
        // The upper bound matters too: BCrypt silently ignores input past 72 bytes,
        // so accepting a longer password would quietly discard the tail of it.
        @NotBlank(message = "Password is required")
        @Size(min = 12, max = 72, message = "Password must be between 12 and 72 characters")
        String password,

        @NotBlank(message = "Full name is required")
        @Size(max = 200, message = "Full name must be at most 200 characters")
        String fullName) {
}
