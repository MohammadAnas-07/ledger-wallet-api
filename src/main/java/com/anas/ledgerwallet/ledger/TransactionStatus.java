package com.anas.ledgerwallet.ledger;

/**
 * Outcome of a transaction.
 *
 * <p>Only {@link #COMPLETED} is ever written: a transaction that fails is rolled back
 * with its entries, so it leaves no row at all. FAILED exists for Phase 7, where an
 * event stream needs a way to describe an attempt that was rejected.
 */
public enum TransactionStatus {
    COMPLETED,
    FAILED
}
