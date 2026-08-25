package com.anas.ledgerwallet.auth;

/** Raised when a registration targets an email that already has an account. */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("An account with this email already exists");
    }
}
