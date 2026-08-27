package com.anas.ledgerwallet.ledger.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A transfer between two accounts.
 *
 * <p>The source account is named in the body rather than taken from the caller, but
 * that is not a way to act as someone else: the service checks that the caller owns
 * whichever source they name.
 *
 * @param idempotencyKey optional, and strongly recommended here. A transfer can come
 *     back as 409 under contention, and both the client and the server may retry —
 *     the key is what stops a retry from moving the money twice.
 */
public record TransferRequest(

        @NotNull(message = "Source account id is required")
        UUID fromAccountId,

        @NotNull(message = "Destination account id is required")
        UUID toAccountId,

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero")
        @Digits(integer = 17, fraction = 2,
                message = "Amount must have at most 2 decimal places")
        BigDecimal amount,

        @Size(max = 64, message = "Idempotency key must be at most 64 characters")
        String idempotencyKey) {
}
