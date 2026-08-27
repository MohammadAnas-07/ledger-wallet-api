package com.anas.ledgerwallet.ledger.event;

import com.anas.ledgerwallet.ledger.TransactionStatus;
import com.anas.ledgerwallet.ledger.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An in-process signal that a transaction was written.
 *
 * <p>Carries plain values rather than entities on purpose. The listener runs after the
 * database transaction has committed and its persistence context is gone, so anything
 * lazy would fail there — and it would fail at the moment the audit trail is being
 * produced, which is the worst possible place for it.
 *
 * <p>The event id is generated once, here, rather than at send time. A resend must
 * reuse it, since that id is what lets the consumer tell a redelivery from a second
 * event.
 */
public record TransactionCompletedEvent(
        UUID eventId,
        UUID transactionId,
        TransactionType type,
        BigDecimal amount,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal fromBalanceAfter,
        BigDecimal toBalanceAfter,
        TransactionStatus status,
        Instant occurredAt) {

    public static TransactionCompletedEvent of(
            UUID transactionId,
            TransactionType type,
            BigDecimal amount,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal fromBalanceAfter,
            BigDecimal toBalanceAfter,
            TransactionStatus status,
            Instant occurredAt) {

        return new TransactionCompletedEvent(
                UUID.randomUUID(), transactionId, type, amount, fromAccountId, toAccountId,
                fromBalanceAfter, toBalanceAfter, status, occurredAt);
    }

    /**
     * The Kafka partition key.
     *
     * <p>Same key means same partition means ordering is preserved per account, which
     * is the one ordering guarantee an audit log actually needs. A deposit's source is
     * the system account, so the user's side is keyed on instead — otherwise every
     * deposit in the system would land on one partition.
     */
    public String partitionKey() {
        return type == TransactionType.DEPOSIT
                ? toAccountId.toString()
                : fromAccountId.toString();
    }
}
