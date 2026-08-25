-- Accounts: the wallets a user holds. One user may own many.

CREATE TABLE accounts (
    id             UUID          PRIMARY KEY,
    user_id        UUID          NOT NULL REFERENCES users (id),
    account_number VARCHAR(24)   NOT NULL,
    balance        NUMERIC(19,2) NOT NULL DEFAULT 0,
    status         VARCHAR(16)   NOT NULL,
    -- Optimistic locking column. Declared here but not exercised until Phase 4,
    -- when money starts moving; Hibernate maintains it from the @Version field.
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL,

    -- A backstop, not the mechanism. The application must never let a balance go
    -- negative on its own; if this constraint ever fires, that is a bug worth a
    -- failed transaction rather than a silently corrupted ledger.
    CONSTRAINT ck_accounts_balance_non_negative CHECK (balance >= 0)
);

CREATE UNIQUE INDEX ux_accounts_account_number ON accounts (account_number);

-- Every account query is scoped to an owner, starting with "list my accounts".
CREATE INDEX ix_accounts_user_id ON accounts (user_id);
