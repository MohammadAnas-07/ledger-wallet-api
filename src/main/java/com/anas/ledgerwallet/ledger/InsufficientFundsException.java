package com.anas.ledgerwallet.ledger;

/**
 * Raised when an account cannot cover a withdrawal.
 *
 * <p>Carries no balance figure: the message reaches the caller, and a refusal should
 * not report how much is in the account.
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException() {
        super("Account balance is insufficient for this operation");
    }
}
