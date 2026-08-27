package com.anas.ledgerwallet.ledger;

/**
 * Raised when a transfer names the same account as source and destination.
 *
 * <p>Such a transfer would debit and credit the same row, netting to nothing while
 * still writing entries and consuming an idempotency key.
 */
public class SelfTransferException extends RuntimeException {

    public SelfTransferException() {
        super("Source and destination accounts must be different");
    }
}
