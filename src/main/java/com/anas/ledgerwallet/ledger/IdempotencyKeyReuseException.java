package com.anas.ledgerwallet.ledger;

/**
 * Raised when a caller reuses one of their own idempotency keys for a different
 * request.
 *
 * <p>Refused rather than replayed: a replay is only correct when the request matches
 * the one that was already applied. Handing back the original result for a different
 * amount or a different pair of accounts would report a movement the caller never
 * asked for as though it had just succeeded, and the request they actually sent would
 * vanish without a trace.
 *
 * <p>Names no detail of the original transaction — the caller is told their key is
 * already spent, not what it was spent on.
 */
public class IdempotencyKeyReuseException extends RuntimeException {

    public IdempotencyKeyReuseException() {
        super("This idempotency key was already used for a different request");
    }
}
