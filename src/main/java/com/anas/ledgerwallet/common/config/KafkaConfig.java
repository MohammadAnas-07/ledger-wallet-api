package com.anas.ledgerwallet.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka topics and consumer error handling.
 *
 * <p>Topics are declared here rather than left to broker auto-creation, which is
 * disabled in compose. Auto-created topics take the broker's defaults — usually one
 * partition — so the per-account ordering the design depends on would quietly not
 * exist, and nothing would report a problem.
 */
@Configuration
public class KafkaConfig {

    /** Three, so consumers can be scaled out; one broker locally means one replica. */
    private static final int PARTITIONS = 3;
    private static final short REPLICAS = 1;

    private final String transactionTopic;

    public KafkaConfig(@Value("${app.kafka.transaction-topic}") String transactionTopic) {
        this.transactionTopic = transactionTopic;
    }

    @Bean
    public NewTopic transactionEventsTopic() {
        return TopicBuilder.name(transactionTopic)
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    /**
     * The dead letter topic, with the same partition count as its source.
     *
     * <p>The recoverer publishes to the same partition number it read from, so a
     * narrower DLT would fail to accept records from the higher partitions.
     */
    @Bean
    public NewTopic transactionEventsDeadLetterTopic() {
        return TopicBuilder.name(transactionTopic + ".DLT")
                .partitions(PARTITIONS)
                .replicas(REPLICAS)
                .build();
    }

    /**
     * Sends messages that keep failing to the dead letter topic instead of retrying
     * them forever.
     *
     * <p>Without this a single unprocessable record blocks its partition permanently:
     * Kafka will not advance past an offset that never completes, so one bad message
     * stops every later event for those accounts. Two quick retries cover a transient
     * blip; anything worse is set aside for inspection so the stream keeps moving.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        return new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(template),
                new FixedBackOff(500L, 2L));
    }
}
