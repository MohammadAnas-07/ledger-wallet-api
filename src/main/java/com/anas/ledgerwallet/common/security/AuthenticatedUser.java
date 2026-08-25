package com.anas.ledgerwallet.common.security;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated caller, as held in the {@code SecurityContext}.
 *
 * <p>Carries the user id, which is what every later phase actually needs: ownership
 * checks compare against {@code id}, never against an id taken from the request body
 * or path (rules.md 2.1).
 */
public class AuthenticatedUser implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;

    public AuthenticatedUser(UUID id, String email, String passwordHash) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // No roles yet: every authenticated user has the same capabilities, and
        // authorisation is per-resource ownership rather than role-based.
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String toString() {
        // Explicit, so the password hash cannot reach a log line through a default
        // or generated implementation.
        return "AuthenticatedUser{id=" + id + ", email=" + email + "}";
    }
}
