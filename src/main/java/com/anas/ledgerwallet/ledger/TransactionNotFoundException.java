package com.anas.ledgerwallet.ledger;

import java.util.UUID;

/** Raised when no transaction exists for the given id. */
public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(UUID transactionId) {
        super("No transaction found with id " + transactionId);
    }
}
