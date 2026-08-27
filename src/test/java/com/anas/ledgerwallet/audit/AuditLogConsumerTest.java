package com.anas.ledgerwallet.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anas.ledgerwallet.ledger.TransactionStatus;
import com.anas.ledgerwallet.ledger.TransactionType;
import com.anas.ledgerwallet.ledger.event.TransactionEventPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class AuditLogConsumerTest {

    @Mock private AuditLogRepository auditLogRepository;

    @InjectMocks private AuditLogConsumer consumer;

    private static TransactionEventPayload payload(UUID eventId) {
        return new TransactionEventPayload(
                eventId, UUID.randomUUID(), TransactionType.TRANSFER,
                new BigDecimal("25.00"), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("75.00"), new BigDecimal("125.00"),
                TransactionStatus.COMPLETED, Instant.now());
    }

    @Test
    @DisplayName("A new event is written to the audit log")
    void recordsNewEvent() {
        TransactionEventPayload event = payload(UUID.randomUUID());
        when(auditLogRepository.existsByEventId(event.eventId())).thenReturn(false);

        consumer.onTransactionEvent(event);

        verify(auditLogRepository).save(any(AuditLogEntry.class));
    }

    @Test
    @DisplayName("A redelivered event is not written twice")
    void ignoresRedelivery() {
        TransactionEventPayload event = payload(UUID.randomUUID());
        when(auditLogRepository.existsByEventId(event.eventId())).thenReturn(true);

        consumer.onTransactionEvent(event);

        // Kafka guarantees at-least-once delivery, so this path is normal operation
        // rather than an error case.
        verify(auditLogRepository, never()).save(any(AuditLogEntry.class));
    }

    @Test
    @DisplayName("Losing the race to the unique index is not an error")
    void toleratesConcurrentInsert() {
        TransactionEventPayload event = payload(UUID.randomUUID());
        when(auditLogRepository.existsByEventId(event.eventId())).thenReturn(false);
        when(auditLogRepository.save(any(AuditLogEntry.class)))
                .thenThrow(new DataIntegrityViolationException("ux_audit_log_event_id"));

        // Two consumers can both pass the exists check; the index settles it. Either
        // way the record is present, which is the outcome that matters, so this must
        // not propagate and send an otherwise fine message to the DLT.
        consumer.onTransactionEvent(event);
    }
}
