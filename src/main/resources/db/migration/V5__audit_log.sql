-- Immutable audit records, written by the Kafka consumer.
--
-- Deliberately has no foreign key to transactions. The audit log is a downstream
-- consumer's own record, fed only by the event stream: it happens to share a database
-- here, but it must not depend on the write side's tables to be valid. A foreign key
-- would quietly couple the two and make the consumer unable to run against its own
-- store.

CREATE TABLE audit_log (
    id              UUID          PRIMARY KEY,
    -- Identifies the event, not the transaction. A single transaction can be
    -- delivered more than once; the unique index below is what makes reprocessing
    -- a redelivery rather than a duplicate.
    event_id        UUID          NOT NULL,
    transaction_id  UUID          NOT NULL,
    type            VARCHAR(16)   NOT NULL,
    amount          NUMERIC(19,2) NOT NULL,
    from_account_id UUID          NOT NULL,
    to_account_id   UUID          NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    -- When the money actually moved.
    occurred_at     TIMESTAMPTZ   NOT NULL,
    -- When this consumer wrote it down. The gap between the two is consumer lag.
    recorded_at     TIMESTAMPTZ   NOT NULL
);

-- At-least-once delivery is the guarantee Kafka gives; this index is what turns it
-- into exactly-once persistence.
CREATE UNIQUE INDEX ux_audit_log_event_id ON audit_log (event_id);

CREATE INDEX ix_audit_log_transaction_id ON audit_log (transaction_id);
