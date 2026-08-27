package com.anas.ledgerwallet.ledger.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.anas.ledgerwallet.ledger.TransactionStatus;
import com.anas.ledgerwallet.ledger.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionCompletedEventTest {

    private static TransactionCompletedEvent event(
            TransactionType type, UUID from, UUID to) {

        return TransactionCompletedEvent.of(
                UUID.randomUUID(), type, new BigDecimal("50.00"), from, to,
                new BigDecimal("10.00"), new BigDecimal("60.00"),
                TransactionStatus.COMPLETED, Instant.now());
    }

    @Test
    @DisplayName("A withdrawal is keyed on the account the money left")
    void keysWithdrawalOnSource() {
        UUID user = UUID.randomUUID();
        UUID system = UUID.randomUUID();

        assertThat(event(TransactionType.WITHDRAWAL, user, system).partitionKey())
                .isEqualTo(user.toString());
    }

    @Test
    @DisplayName("A transfer is keyed on the sending account")
    void keysTransferOnSource() {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();

        assertThat(event(TransactionType.TRANSFER, sender, recipient).partitionKey())
                .isEqualTo(sender.toString());
    }

    @Test
    @DisplayName("A deposit is keyed on the receiving account, not the system account")
    void keysDepositOnDestination() {
        UUID system = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        // Every deposit shares the same source. Keying on it would funnel all of them
        // onto one partition and lose per-account ordering for the accounts that
        // actually matter.
        assertThat(event(TransactionType.DEPOSIT, system, user).partitionKey())
                .isEqualTo(user.toString());
    }

    @Test
    @DisplayName("Each event gets its own id")
    void generatesDistinctEventIds() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        // The id identifies the delivery-deduplication unit, not the transaction, so
        // two events must never collide.
        assertThat(event(TransactionType.TRANSFER, from, to).eventId())
                .isNotEqualTo(event(TransactionType.TRANSFER, from, to).eventId());
    }

    @Test
    @DisplayName("The payload carries every field the event holds")
    void payloadMirrorsEvent() {
        TransactionCompletedEvent source =
                event(TransactionType.TRANSFER, UUID.randomUUID(), UUID.randomUUID());

        TransactionEventPayload payload = TransactionEventPayload.from(source);

        assertThat(payload.eventId()).isEqualTo(source.eventId());
        assertThat(payload.transactionId()).isEqualTo(source.transactionId());
        assertThat(payload.type()).isEqualTo(source.type());
        assertThat(payload.amount()).isEqualByComparingTo(source.amount());
        assertThat(payload.fromAccountId()).isEqualTo(source.fromAccountId());
        assertThat(payload.toAccountId()).isEqualTo(source.toAccountId());
        assertThat(payload.fromBalanceAfter()).isEqualByComparingTo(source.fromBalanceAfter());
        assertThat(payload.toBalanceAfter()).isEqualByComparingTo(source.toBalanceAfter());
        assertThat(payload.status()).isEqualTo(source.status());
        assertThat(payload.occurredAt()).isEqualTo(source.occurredAt());
    }
}
