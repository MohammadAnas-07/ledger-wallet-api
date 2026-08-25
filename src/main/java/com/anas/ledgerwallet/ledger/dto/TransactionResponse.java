package com.anas.ledgerwallet.ledger.dto;

import com.anas.ledgerwallet.ledger.Transaction;
import com.anas.ledgerwallet.ledger.TransactionStatus;
import com.anas.ledgerwallet.ledger.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The result of a money movement.
 *
 * <p>Reports the balance of the account the caller acted on, not both sides: the
 * counterparty here is the system account, which is none of the caller's business.
 */
public record TransactionResponse(
        UUID transactionId,
        TransactionType type,
        BigDecimal amount,
        UUID accountId,
        BigDecimal balanceAfter,
        TransactionStatus status,
        Instant createdAt) {

    public static TransactionResponse of(
            Transaction transaction, UUID accountId, BigDecimal balanceAfter) {

        return new TransactionResponse(
                transaction.getId(),
                transaction.getType(),
                transaction.getAmount(),
                accountId,
                balanceAfter,
                transaction.getStatus(),
                transaction.getCreatedAt());
    }
}
