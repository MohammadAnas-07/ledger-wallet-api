package com.anas.ledgerwallet.ledger.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A deposit or withdrawal request.
 *
 * <p>The amount is a {@code BigDecimal}, never a {@code double}: binary floating point
 * cannot represent decimal currency exactly, and the drift would break the ledger
 * invariants (rules.md 2.3).
 *
 * @param idempotencyKey optional; when supplied, repeating a request that already
 *     succeeded returns the original result instead of moving money a second time.
 *     This is what makes a client retry after a 409 safe.
 */
public record MoneyMovementRequest(

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than zero")
        @Digits(integer = 17, fraction = 2,
                message = "Amount must have at most 2 decimal places")
        BigDecimal amount,

        @Size(max = 64, message = "Idempotency key must be at most 64 characters")
        String idempotencyKey) {
}
