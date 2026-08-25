package com.anas.ledgerwallet.account;

import com.anas.ledgerwallet.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A wallet belonging to a user.
 *
 * <p>The balance is stored as {@code BigDecimal} with a fixed scale of 2 and is never
 * a {@code double} — binary floating point cannot represent decimal currency exactly,
 * and the rounding drift would break the ledger invariants this project exists to
 * guarantee (rules.md 2.3).
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @Column(name = "account_number", nullable = false, unique = true, length = 24)
    private String accountNumber;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    /**
     * Optimistic lock version, maintained by Hibernate.
     *
     * <p>Nothing updates a balance yet, so this does no work in Phase 3. From Phase 4
     * it is what stops two concurrent writers from silently overwriting each other:
     * the UPDATE carries the version it read, and the losing transaction matches zero
     * rows and fails instead of losing an update (architecture.md 3).
     */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
        // Required by JPA.
    }

    public Account(User owner, String accountNumber, Instant createdAt) {
        this.owner = owner;
        this.accountNumber = accountNumber;
        this.balance = BigDecimal.ZERO.setScale(2);
        this.status = AccountStatus.ACTIVE;
        this.createdAt = createdAt;
    }

    /**
     * True when this account belongs to the given user.
     *
     * <p>Lives on the entity so every caller asks the same question the same way,
     * rather than each one re-deriving the comparison.
     */
    public boolean isOwnedBy(UUID userId) {
        return owner.getId().equals(userId);
    }

    public UUID getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
