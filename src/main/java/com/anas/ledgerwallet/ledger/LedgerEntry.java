package com.anas.ledgerwallet.ledger;

import com.anas.ledgerwallet.account.Account;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One side of a transaction: money leaving or entering a single account.
 *
 * <p><strong>Append-only.</strong> Never updated, never deleted — a correction is a
 * new, reversing transaction. That is what makes the ledger an audit trail rather
 * than a mutable summary.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private EntryDirection direction;

    /** Positive magnitude. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Negative for a debit, positive for a credit. See {@link EntryDirection#sign}. */
    @Column(name = "signed_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal signedAmount;

    /** The account balance immediately after this entry, for the statement view. */
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // Required by JPA.
    }

    public LedgerEntry(
            Transaction transaction,
            Account account,
            EntryDirection direction,
            BigDecimal amount,
            BigDecimal balanceAfter,
            Instant createdAt) {
        this.transaction = transaction;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
        this.signedAmount = direction.sign(amount);
        this.balanceAfter = balanceAfter;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public Account getAccount() {
        return account;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getSignedAmount() {
        return signedAmount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
