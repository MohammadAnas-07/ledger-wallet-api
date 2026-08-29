-- An idempotency key belongs to the caller who chose it, not to the whole system.
--
-- Until now the unique index was global and the replay lookup was unscoped, so a key
-- one user had already spent answered for anyone who sent it: the replay returned that
-- user's transaction id, amount and resulting balance to a stranger, and a key
-- deliberately taken first made the rightful owner's request return 201 while moving
-- no money. Reading the key as (initiator, key) closes both.

ALTER TABLE transactions ADD COLUMN initiated_by UUID REFERENCES users (id);

-- Backfill: the initiator is the owner of whichever side is not the system account.
-- A deposit is credited to the caller; a withdrawal and a transfer are debited from
-- them; no transaction has the system account on both sides, so exactly one side
-- always resolves.
UPDATE transactions t
SET initiated_by = COALESCE(
        (SELECT a.user_id FROM accounts a WHERE a.id = t.from_account_id AND NOT a.is_system),
        (SELECT a.user_id FROM accounts a WHERE a.id = t.to_account_id   AND NOT a.is_system));

ALTER TABLE transactions ALTER COLUMN initiated_by SET NOT NULL;

DROP INDEX ux_transactions_idempotency_key;

-- Still partial, for the same reason as before: callers who send no key must not all
-- collide on NULL. Two users may now choose the same key without meeting.
CREATE UNIQUE INDEX ux_transactions_initiator_idempotency_key
    ON transactions (initiated_by, idempotency_key) WHERE idempotency_key IS NOT NULL;
