package com.anas.ledgerwallet.ledger;

import com.anas.ledgerwallet.account.Account;
import com.anas.ledgerwallet.account.AccountNotFoundException;
import com.anas.ledgerwallet.account.AccountRepository;
import com.anas.ledgerwallet.account.AccountService;
import com.anas.ledgerwallet.ledger.dto.MoneyMovementRequest;
import com.anas.ledgerwallet.ledger.dto.TransactionResponse;
import com.anas.ledgerwallet.ledger.dto.TransferResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money movement: deposits, withdrawals, and transfers.
 *
 * <p>Every movement is written as a transaction header plus exactly two ledger
 * entries — a debit and a matching credit — inside one database transaction. Both
 * balances and both entries commit together or not at all, so there is no state in
 * which one side moved and the other did not (prd.md, Invariant 4).
 *
 * <p>The three operations differ only in who the two sides are. A deposit or
 * withdrawal uses the system account as counterparty; a transfer uses two real
 * accounts. The posting itself is identical, which is why it lives in one place.
 */
@Service
public class LedgerService {

    /** Seeded by V4. Fixed so it needs no lookup by name. */
    public static final UUID SYSTEM_ACCOUNT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final int MONEY_SCALE = 2;

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final TransactionRepository transactionRepository;

    public LedgerService(
            AccountRepository accountRepository,
            AccountService accountService,
            TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
    }

    /** Money in: the system account is debited, the caller's account credited. */
    @Transactional
    public TransactionResponse deposit(
            UUID accountId, UUID callerId, MoneyMovementRequest request) {

        Posted posted = post(TransactionType.DEPOSIT, SYSTEM_ACCOUNT_ID, accountId,
                accountId, callerId, request.amount(), request.idempotencyKey());

        return TransactionResponse.of(posted.transaction(), accountId, posted.creditedBalance());
    }

    /** Money out: the caller's account is debited, the system account credited. */
    @Transactional
    public TransactionResponse withdraw(
            UUID accountId, UUID callerId, MoneyMovementRequest request) {

        Posted posted = post(TransactionType.WITHDRAWAL, accountId, SYSTEM_ACCOUNT_ID,
                accountId, callerId, request.amount(), request.idempotencyKey());

        return TransactionResponse.of(posted.transaction(), accountId, posted.debitedBalance());
    }

    /**
     * Moves money between two real accounts.
     *
     * <p>Deliberately not annotated {@code @Retryable}: the retry lives on
     * {@code TransferService}, one bean out, so each attempt begins a fresh
     * transaction. Retrying inside a transaction that has already been marked
     * rollback-only would achieve nothing.
     */
    @Transactional
    public TransferResponse transfer(
            UUID fromAccountId,
            UUID toAccountId,
            UUID callerId,
            BigDecimal amount,
            String idempotencyKey) {

        if (fromAccountId.equals(toAccountId)) {
            // Caught before any read: the two sides would be the same row, so the
            // debit and credit would cancel and the transaction would be a no-op that
            // still consumed an idempotency key.
            throw new SelfTransferException();
        }

        Account destination = accountRepository.findById(toAccountId)
                .orElseThrow(() -> new AccountNotFoundException(toAccountId));

        if (destination.isSystem()) {
            // The system account is an internal counterparty, not a destination anyone
            // may address. Reported as not found rather than forbidden, because from
            // the caller's point of view it is not an account that exists.
            throw new AccountNotFoundException(toAccountId);
        }

        Posted posted = post(TransactionType.TRANSFER, fromAccountId, toAccountId,
                fromAccountId, callerId, amount, idempotencyKey);

        return TransferResponse.of(
                posted.transaction(), fromAccountId, toAccountId, posted.debitedBalance());
    }

    /**
     * Writes one transaction and its two entries, and moves both balances.
     *
     * @param ownedAccountId the account the caller must own. For a transfer this is
     *     the source only — you may credit anyone, but you may only debit yourself
     *     (architecture.md 5).
     */
    private Posted post(
            TransactionType type,
            UUID debitAccountId,
            UUID creditAccountId,
            UUID ownedAccountId,
            UUID callerId,
            BigDecimal requestedAmount,
            String rawIdempotencyKey) {

        // Ownership first: refuse before reading or writing anything else, so a
        // rejection never depends on the state of an account the caller cannot see.
        accountService.loadOwnedAccount(ownedAccountId, callerId);

        String idempotencyKey = blankToNull(rawIdempotencyKey);

        Optional<Posted> replay = replayIfAlreadyApplied(idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        BigDecimal amount = requestedAmount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);

        Account debited = loadForUpdate(debitAccountId);
        Account credited = loadForUpdate(creditAccountId);

        if (!debited.hasSufficientFunds(amount)) {
            // Thrown before any write, so the rejected attempt leaves no ledger entry
            // and no balance change behind.
            throw new InsufficientFundsException();
        }

        Instant now = Instant.now();
        Transaction transaction =
                new Transaction(type, amount, debited, credited, idempotencyKey, now);

        applyMovementsInLockOrder(debited, credited, amount);

        transaction.addEntry(new LedgerEntry(
                transaction, debited, EntryDirection.DEBIT, amount, debited.getBalance(), now));
        transaction.addEntry(new LedgerEntry(
                transaction, credited, EntryDirection.CREDIT, amount, credited.getBalance(), now));

        // Flushed inside the transaction so an optimistic lock conflict surfaces here,
        // as a rollback of the whole unit, rather than at some later commit point.
        Transaction saved = transactionRepository.saveAndFlush(transaction);

        return new Posted(saved, debited.getBalance(), credited.getBalance());
    }

    /**
     * Updates both balances in a globally consistent order, lowest account id first.
     *
     * <p><strong>This is what prevents database deadlock.</strong> Optimistic locking
     * takes no explicit locks, but every {@code UPDATE} still holds a row-level write
     * lock until the transaction commits. Two transfers in opposite directions —
     * A to B and B to A — would each grab one row and then wait for the other, and
     * PostgreSQL would break the cycle by killing one with a deadlock error. Applying
     * every movement in ascending id order means no cycle can form in the first place.
     *
     * <p>Each account is flushed individually and deliberately. Without the flush,
     * Hibernate decides when to issue the two UPDATEs and in what order, so the order
     * the rows are actually locked in would not be the order written here — the fix
     * would look correct and do nothing.
     */
    private void applyMovementsInLockOrder(
            Account debited, Account credited, BigDecimal amount) {

        boolean debitFirst = debited.getId().compareTo(credited.getId()) < 0;

        Account first = debitFirst ? debited : credited;
        Account second = debitFirst ? credited : debited;
        BigDecimal firstAmount = debitFirst
                ? EntryDirection.DEBIT.sign(amount)
                : EntryDirection.CREDIT.sign(amount);
        BigDecimal secondAmount = debitFirst
                ? EntryDirection.CREDIT.sign(amount)
                : EntryDirection.DEBIT.sign(amount);

        first.applyMovement(firstAmount);
        accountRepository.saveAndFlush(first);

        second.applyMovement(secondAmount);
        accountRepository.saveAndFlush(second);
    }

    /**
     * Returns the original result when this idempotency key has already been applied.
     *
     * <p>This is what makes retrying after a 409 safe — including the server's own
     * bounded retry. Without it, a retry of a request that actually committed would
     * move the money a second time.
     */
    private Optional<Posted> replayIfAlreadyApplied(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }

        return transactionRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> new Posted(
                        existing,
                        currentBalanceOf(existing.getFromAccount().getId()),
                        currentBalanceOf(existing.getToAccount().getId())));
    }

    private BigDecimal currentBalanceOf(UUID accountId) {
        return accountRepository.findById(accountId)
                .map(Account::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Loads an account so that its {@code @Version} is read as part of this
     * transaction.
     *
     * <p>No lock is taken. Hibernate appends the version it read to the UPDATE, and if
     * another transaction committed in the meantime the update matches zero rows and
     * this one fails rather than silently overwriting it (architecture.md 3).
     */
    private Account loadForUpdate(UUID accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** The outcome of a posting: the saved transaction and both resulting balances. */
    private record Posted(
            Transaction transaction, BigDecimal debitedBalance, BigDecimal creditedBalance) {
    }
}
