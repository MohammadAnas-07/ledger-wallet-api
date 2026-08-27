package com.anas.ledgerwallet.ledger.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes committed transactions to Kafka.
 *
 * <p><strong>{@code AFTER_COMMIT} is the whole design.</strong> If the send happened
 * inside the transaction and that transaction then rolled back — an optimistic lock
 * conflict, insufficient funds, a constraint violation — the audit log would record
 * money movement that never happened. Kafka has no transaction to roll back with, so
 * the only way to prevent a fabricated record is to send after the database has
 * already committed. An event on the topic is therefore proof that a transaction
 * committed (prd.md, Invariant 5).
 *
 * <p>The residual failure runs the other way: the commit succeeds, the broker is
 * unreachable, and a real transaction goes unpublished. That is the acknowledged
 * trade-off and it is the safe direction — a missing audit record can be rebuilt by
 * replaying the ledger, a fabricated one cannot be detected at all. A transactional
 * outbox would close the gap and is noted in architecture.md as a future step.
 */
@Component
public class TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);

    private final KafkaTemplate<String, TransactionEventPayload> kafkaTemplate;
    private final String topic;

    public TransactionEventPublisher(
            KafkaTemplate<String, TransactionEventPayload> kafkaTemplate,
            @org.springframework.beans.factory.annotation.Value("${app.kafka.transaction-topic}")
            String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionCommitted(TransactionCompletedEvent event) {
        TransactionEventPayload payload = TransactionEventPayload.from(event);

        kafkaTemplate.send(topic, event.partitionKey(), payload)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        // Logged loudly rather than rethrown: the money has already
                        // moved and committed. Failing the request now would tell the
                        // caller their transaction did not happen, which is false.
                        log.error("Failed to publish transaction event {} for transaction {}",
                                event.eventId(), event.transactionId(), failure);
                    } else {
                        log.debug("Published transaction event {} to {}-{}",
                                event.eventId(),
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition());
                    }
                });
    }
}
