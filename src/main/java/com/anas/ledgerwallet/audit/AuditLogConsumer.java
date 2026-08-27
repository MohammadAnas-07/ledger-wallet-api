package com.anas.ledgerwallet.audit;

import com.anas.ledgerwallet.ledger.event.TransactionEventPayload;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns the event stream into a durable audit trail.
 *
 * <p>Kafka guarantees at-least-once delivery, so this has to be safe to run twice on
 * the same message — a rebalance or a redelivery after a slow commit will do exactly
 * that. Idempotency comes from the event id: a second delivery finds the row already
 * there and does nothing.
 */
@Component
public class AuditLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditLogConsumer.class);

    private final AuditLogRepository auditLogRepository;

    public AuditLogConsumer(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @KafkaListener(
            topics = "${app.kafka.transaction-topic}",
            groupId = "${app.kafka.audit-group-id}")
    @Transactional
    public void onTransactionEvent(TransactionEventPayload payload) {
        if (auditLogRepository.existsByEventId(payload.eventId())) {
            log.debug("Skipping already recorded event {}", payload.eventId());
            return;
        }

        try {
            auditLogRepository.save(new AuditLogEntry(
                    payload.eventId(),
                    payload.transactionId(),
                    payload.type(),
                    payload.amount(),
                    payload.fromAccountId(),
                    payload.toAccountId(),
                    payload.status(),
                    payload.occurredAt(),
                    Instant.now()));

            log.info("Audited {} transaction {} for {} (event {})",
                    payload.type(), payload.transactionId(), payload.amount(),
                    payload.eventId());

        } catch (DataIntegrityViolationException e) {
            // Two consumers processing the same redelivery can both pass the check
            // above; only the unique index settles it. Losing that race means the
            // record exists, which is the desired end state either way.
            log.debug("Event {} was recorded concurrently", payload.eventId());
        }
    }
}
