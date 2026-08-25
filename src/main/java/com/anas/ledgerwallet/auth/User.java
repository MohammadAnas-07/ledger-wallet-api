package com.anas.ledgerwallet.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A registered user. Owns one or more accounts from Phase 3 onward.
 *
 * <p>Never returned from a controller. Serialising this entity would put
 * {@code passwordHash} on the wire, so the API returns DTOs instead (rules.md 4.4).
 * For the same reason there is no generated {@code toString()}: a Lombok
 * {@code @Data} here would print the hash into any log line that touched the object.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue
    private UUID id;

    /** Always stored lower-cased; normalisation happens in {@code AuthService}. */
    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {
        // Required by JPA.
    }

    public User(String email, String passwordHash, String fullName, Instant createdAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
