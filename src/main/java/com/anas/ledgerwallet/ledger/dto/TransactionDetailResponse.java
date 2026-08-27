package com.anas.ledgerwallet.ledger.dto;

import com.anas.ledgerwallet.ledger.EntryDirection;
import com.anas.ledgerwallet.ledger.LedgerEntry;
import com.anas.ledgerwallet.ledger.Transaction;
import com.anas.ledgerwallet.ledger.TransactionStatus;
import com.anas.ledgerwallet.ledger.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A single transaction with both of its ledger entries — the double entry itself,
 * rather than one account's view of it.
 *
 * <p>Deliberately omits {@code balanceAfter}. A transfer's two entries belong to two
 * different people, and this endpoint is reachable by either of them; publishing the
 * recorded balances here would hand each party the other's balance at that moment.
 * The statement endpoint already gives a caller their own running balance.
 */
public record TransactionDetailResponse(
        UUID id,
        TransactionType type,
        BigDecimal amount,
        TransactionStatus status,
        Instant createdAt,
        List<EntryResponse> entries) {

    /**
     * One side of the transaction.
     *
     * @param external true when this side is the system account. Its id and number are
     *     withheld — it is internal plumbing, and naming it would invite clients to
     *     address it. The entry is still shown so the pair visibly sums to zero.
     */
    public record EntryResponse(
            UUID accountId,
            String accountNumber,
            boolean external,
            EntryDirection direction,
            BigDecimal amount,
            BigDecimal signedAmount) {

        static EntryResponse from(LedgerEntry entry) {
            boolean external = entry.getAccount().isSystem();

            return new EntryResponse(
                    external ? null : entry.getAccount().getId(),
                    external ? null : entry.getAccount().getAccountNumber(),
                    external,
                    entry.getDirection(),
                    entry.getAmount(),
                    entry.getSignedAmount());
        }
    }

    public static TransactionDetailResponse from(Transaction transaction) {
        List<EntryResponse> entries = transaction.getEntries().stream()
                // Debit first, so the pair reads the way it would on paper.
                .sorted(Comparator.comparing(LedgerEntry::getDirection))
                .map(EntryResponse::from)
                .toList();

        return new TransactionDetailResponse(
                transaction.getId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getStatus(),
                transaction.getCreatedAt(),
                entries);
    }
}
