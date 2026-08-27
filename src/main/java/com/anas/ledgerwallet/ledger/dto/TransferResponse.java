package com.anas.ledgerwallet.ledger.dto;

import com.anas.ledgerwallet.ledger.Transaction;
import com.anas.ledgerwallet.ledger.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The result of a transfer.
 *
 * <p>Reports the sender's resulting balance only. The destination account belongs to
 * someone else — often another user entirely — and being able to send them money is
 * not a reason to learn what they hold. Sending a small amount repeatedly would
 * otherwise turn this endpoint into a balance oracle for any account whose id is
 * known.
 */
public record TransferResponse(
        UUID transactionId,
        BigDecimal amount,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal fromBalanceAfter,
        TransactionStatus status,
        Instant createdAt) {

    public static TransferResponse of(
            Transaction transaction,
            UUID fromAccountId,
            UUID toAccountId,
            BigDecimal fromBalanceAfter) {

        return new TransferResponse(
                transaction.getId(),
                transaction.getAmount(),
                fromAccountId,
                toAccountId,
                fromBalanceAfter,
                transaction.getStatus(),
                transaction.getCreatedAt());
    }
}
