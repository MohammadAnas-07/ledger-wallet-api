package com.anas.ledgerwallet.ledger;

import com.anas.ledgerwallet.account.Account;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One business event — a deposit, a withdrawal, or (from Phase 5) a transfer.
 *
 * <p>The envelope that binds a debit and a credit together. It exists so the pair is
 * created and committed as a unit: entries are what make the ledger auditable, and a
 * lone entry would be money appearing from nowhere.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionType type;

    /** Always positive. Direction is carried by the entries, never by the sign here. */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_account_id", nullable = false)
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_account_id", nullable = false)
    private Account toAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionStatus status;

    /**
     * The user who asked for this movement, and the owner of the idempotency key.
     *
     * <p>Held as a plain id rather than an association: nothing here navigates to the
     * user, and the column exists so a key can be read as (initiator, key). A key is
     * scoped to the caller who chose it — an unscoped one answers for whoever sends
     * it, which leaks the original caller's transaction to a stranger and lets a
     * stranger's key silently swallow a real request.
     */
    @Column(name = "initiated_by", nullable = false)
    private UUID initiatedBy;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LedgerEntry> entries = new ArrayList<>();

    protected Transaction() {
        // Required by JPA.
    }

    public Transaction(
            TransactionType type,
            BigDecimal amount,
            Account fromAccount,
            Account toAccount,
            UUID initiatedBy,
            String idempotencyKey,
            Instant createdAt) {
        this.type = type;
        this.amount = amount;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.initiatedBy = initiatedBy;
        this.idempotencyKey = idempotencyKey;
        this.status = TransactionStatus.COMPLETED;
        this.createdAt = createdAt;
    }

    public void addEntry(LedgerEntry entry) {
        entries.add(entry);
    }

    public UUID getId() {
        return id;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Account getFromAccount() {
        return fromAccount;
    }

    public Account getToAccount() {
        return toAccount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public UUID getInitiatedBy() {
        return initiatedBy;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<LedgerEntry> getEntries() {
        return List.copyOf(entries);
    }
}
