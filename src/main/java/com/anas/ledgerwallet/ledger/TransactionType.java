package com.anas.ledgerwallet.ledger;

/** What kind of business event a transaction records. */
public enum TransactionType {
    /** Money entering the system: debits the system account, credits the user. */
    DEPOSIT,
    /** Money leaving the system: debits the user, credits the system account. */
    WITHDRAWAL,
    /** Money moving between two user accounts. Arrives in Phase 5. */
    TRANSFER
}
