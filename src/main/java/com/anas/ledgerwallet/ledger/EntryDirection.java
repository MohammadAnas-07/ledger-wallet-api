package com.anas.ledgerwallet.ledger;

import java.math.BigDecimal;

/** Which way money moved for one side of a transaction. */
public enum EntryDirection {
    /** Money out of the account. */
    DEBIT,
    /** Money into the account. */
    CREDIT;

    /**
     * Converts a positive magnitude into the signed value stored on the entry.
     *
     * <p>Signing here, in one place, is what lets the system-wide invariant be a
     * single {@code SUM(signed_amount)} instead of a query that re-interprets
     * direction at every call site.
     */
    public BigDecimal sign(BigDecimal amount) {
        return this == DEBIT ? amount.negate() : amount;
    }
}
