package com.anas.ledgerwallet.ledger.dto;

import com.anas.ledgerwallet.account.Account;
import com.anas.ledgerwallet.ledger.EntryDirection;
import com.anas.ledgerwallet.ledger.LedgerEntry;
import com.anas.ledgerwallet.ledger.Transaction;
import com.anas.ledgerwallet.ledger.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of an account statement.
 *
 * <p>Built from a ledger entry rather than a transaction, because direction only means
 * anything relative to an account: the same transfer is a debit on one statement and a
 * credit on the other. Reading the entries for one account gives exactly that view,
 * and {@code balanceAfter} is already recorded on each one.
 *
 * @param counterparty the other side of the transaction, or null when that side is
 *     the system account — a deposit or withdrawal faces the outside world, and the
 *     internal counterparty is not something the API should name.
 */
public record StatementEntryResponse(
        UUID entryId,
        UUID transactionId,
        TransactionType type,
        EntryDirection direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        CounterpartyResponse counterparty,
        Instant createdAt) {

    /** The other account in a transfer. Never carries a balance. */
    public record CounterpartyResponse(UUID accountId, String accountNumber) {
    }

    public static StatementEntryResponse from(LedgerEntry entry) {
        return new StatementEntryResponse(
                entry.getId(),
                entry.getTransaction().getId(),
                entry.getTransaction().getType(),
                entry.getDirection(),
                entry.getAmount(),
                entry.getBalanceAfter(),
                counterpartyOf(entry),
                entry.getCreatedAt());
    }

    private static CounterpartyResponse counterpartyOf(LedgerEntry entry) {
        Transaction transaction = entry.getTransaction();
        UUID thisAccountId = entry.getAccount().getId();

        Account other = transaction.getFromAccount().getId().equals(thisAccountId)
                ? transaction.getToAccount()
                : transaction.getFromAccount();

        if (other.isSystem()) {
            return null;
        }
        return new CounterpartyResponse(other.getId(), other.getAccountNumber());
    }
}
