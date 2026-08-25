package com.anas.ledgerwallet.account;

import java.util.UUID;

/** Raised when no account exists for the given id. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountId) {
        super("No account found with id " + accountId);
    }
}
