package com.anas.ledgerwallet.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login payload.
 *
 * <p>Deliberately looser than {@link RegisterRequest}: only presence is checked. If
 * login re-applied the registration rules, an existing user whose password predates a
 * rule change would be rejected at validation with a 400 describing the policy —
 * which both breaks their login and advertises the current password rules.
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        String email,

        @NotBlank(message = "Password is required")
        String password) {
}
