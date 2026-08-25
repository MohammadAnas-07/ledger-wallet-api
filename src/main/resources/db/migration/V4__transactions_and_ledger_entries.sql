-- Double-entry bookkeeping: transaction headers, ledger entries, and the system
-- account that external money movement posts against.

-- ---------------------------------------------------------------------------
-- The system account
-- ---------------------------------------------------------------------------
-- Deposits and withdrawals move money between a user and the outside world. Without
-- a counterparty they would produce a single one-sided entry, and the invariant that
-- all entries sum to zero would only hold for transfers. The system account is that
-- counterparty: money entering the system is debited from it, money leaving is
-- credited to it.
--
-- It is owned by nobody, so user_id becomes nullable and a CHECK keeps every other
-- account owned. Nobody owning it is what makes it unreachable through the API:
-- every read goes through an ownership comparison that a NULL owner can never pass.
ALTER TABLE accounts ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE accounts ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE accounts ADD CONSTRAINT ck_accounts_owner_required
    CHECK (is_system OR user_id IS NOT NULL);

-- The system account's balance is the negative of all money held by users, so it is
-- expected to run negative. User accounts stay non-negative as before.
ALTER TABLE accounts DROP CONSTRAINT ck_accounts_balance_non_negative;
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_balance_non_negative
    CHECK (is_system OR balance >= 0);

INSERT INTO accounts (id, user_id, account_number, balance, status, version, is_system, created_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    NULL,
    'ACC-SYSTEM',
    0,
    'ACTIVE',
    0,
    true,
    now()
);

-- ---------------------------------------------------------------------------
-- Transaction headers: one row per business event
-- ---------------------------------------------------------------------------
CREATE TABLE transactions (
    id              UUID          PRIMARY KEY,
    type            VARCHAR(16)   NOT NULL,
    -- Always positive. Direction lives on the entries, never on the amount.
    amount          NUMERIC(19,2) NOT NULL,
    from_account_id UUID          NOT NULL REFERENCES accounts (id),
    to_account_id   UUID          NOT NULL REFERENCES accounts (id),
    status          VARCHAR(16)   NOT NULL,
    -- Lets a client safely retry a request that returned 409. Without it, a retry
    -- after an optimistic lock conflict could apply the same movement twice.
    idempotency_key VARCHAR(64),
    created_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transactions_distinct_accounts CHECK (from_account_id <> to_account_id)
);

-- Partial: only rows that actually carry a key are constrained, so callers who omit
-- one are not all colliding on NULL.
CREATE UNIQUE INDEX ux_transactions_idempotency_key
    ON transactions (idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_transactions_created_at ON transactions (created_at);

-- ---------------------------------------------------------------------------
-- Ledger entries: two per transaction, append-only
-- ---------------------------------------------------------------------------
-- Never updated and never deleted. A correction is a new, reversing transaction.
CREATE TABLE ledger_entries (
    id             UUID          PRIMARY KEY,
    transaction_id UUID          NOT NULL REFERENCES transactions (id),
    account_id     UUID          NOT NULL REFERENCES accounts (id),
    direction      VARCHAR(6)    NOT NULL,
    -- Positive magnitude.
    amount         NUMERIC(19,2) NOT NULL,
    -- Negative for a debit, positive for a credit. Exists so the core invariant is a
    -- single SUM() rather than a join that has to interpret direction.
    signed_amount  NUMERIC(19,2) NOT NULL,
    balance_after  NUMERIC(19,2) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_signed_amount CHECK (
        (direction = 'DEBIT'  AND signed_amount = -amount) OR
        (direction = 'CREDIT' AND signed_amount =  amount)
    )
);

-- The statement query in Phase 6 reads one account newest-first.
CREATE INDEX ix_ledger_entries_account_created ON ledger_entries (account_id, created_at DESC);
CREATE INDEX ix_ledger_entries_transaction ON ledger_entries (transaction_id);
