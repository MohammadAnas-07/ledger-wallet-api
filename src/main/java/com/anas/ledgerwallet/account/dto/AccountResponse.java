package com.anas.ledgerwallet.account.dto;

import com.anas.ledgerwallet.account.Account;
import com.anas.ledgerwallet.account.AccountStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public view of an account.
 *
 * <p>Carries no owner id and no lock version: the caller can only ever see their own
 * accounts, and {@code version} is an internal concurrency detail that would invite
 * clients to send it back.
 */
public record AccountResponse(
        UUID id,
        String accountNumber,
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getBalance(),
                account.getStatus(),
                account.getCreatedAt());
    }
}
