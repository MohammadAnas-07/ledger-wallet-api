package com.anas.ledgerwallet.ledger.event;

import com.anas.ledgerwallet.ledger.TransactionStatus;
import com.anas.ledgerwallet.ledger.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The wire format on {@code transaction-events} (architecture.md §4).
 *
 * <p>Separate from {@link TransactionCompletedEvent} because this one is a published
 * contract: consumers deserialise it, so its shape cannot change as freely as an
 * in-process type.
 *
 * <p>It carries both balances, unlike the HTTP responses, which report only the
 * caller's own. The audience is different — this stream feeds an audit consumer, not
 * one of the parties, and an audit record that omitted half the movement would be a
 * poor audit record.
 */
public record TransactionEventPayload(
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

    public static TransactionEventPayload from(TransactionCompletedEvent event) {
        return new TransactionEventPayload(
                event.eventId(),
                event.transactionId(),
                event.type(),
                event.amount(),
                event.fromAccountId(),
                event.toAccountId(),
                event.fromBalanceAfter(),
                event.toBalanceAfter(),
                event.status(),
                event.occurredAt());
    }
}
