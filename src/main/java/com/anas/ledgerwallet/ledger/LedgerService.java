package com.anas.ledgerwallet.ledger;

import com.anas.ledgerwallet.account.Account;
import com.anas.ledgerwallet.account.AccountRepository;
import com.anas.ledgerwallet.account.AccountService;
import com.anas.ledgerwallet.ledger.dto.MoneyMovementRequest;
import com.anas.ledgerwallet.ledger.dto.TransactionResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Money entering and leaving the system.
 *
 * <p>Every movement is written as a transaction header plus exactly two ledger
 * entries — a debit and a matching credit — inside one database transaction. Both
 * balances and both entries commit together or not at all, so there is no state in
 * which one side moved and the other did not (prd.md, Invariant 4).
 *
 * <p>The counterparty for a deposit or withdrawal is the system account. Without it a
 * deposit would be a single one-sided entry and the invariant that all entries sum to
 * zero would hold only for transfers.
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

        return post(TransactionType.DEPOSIT, SYSTEM_ACCOUNT_ID, accountId, accountId,
                callerId, request);
    }

    /** Money out: the caller's account is debited, the system account credited. */
    @Transactional
    public TransactionResponse withdraw(
            UUID accountId, UUID callerId, MoneyMovementRequest request) {

        return post(TransactionType.WITHDRAWAL, accountId, SYSTEM_ACCOUNT_ID, accountId,
                callerId, request);
    }

    /**
     * Writes one transaction and its two entries, and moves both balances.
     *
     * <p>Reused by Phase 5's transfer, which differs only in that both sides are user
     * accounts.
     *
     * @param reportedAccountId which side's resulting balance the caller sees
     */
    private TransactionResponse post(
            TransactionType type,
            UUID debitAccountId,
            UUID creditAccountId,
            UUID reportedAccountId,
            UUID callerId,
            MoneyMovementRequest request) {

        // Ownership first: refuse before reading or writing anything else. The caller
        // must own the account they named, whichever side of the movement it is on.
        accountService.loadOwnedAccount(reportedAccountId, callerId);

        Optional<TransactionResponse> replay = replayIfAlreadyApplied(request, reportedAccountId);
        if (replay.isPresent()) {
            return replay.get();
        }

        BigDecimal amount = request.amount().setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);

        Account debited = loadForUpdate(debitAccountId);
        Account credited = loadForUpdate(creditAccountId);

        if (!debited.hasSufficientFunds(amount)) {
            // Thrown before any write, so the rejected attempt leaves no ledger entry
            // and no balance change behind.
            throw new InsufficientFundsException();
        }

        Instant now = Instant.now();
        Transaction transaction = new Transaction(
                type, amount, debited, credited, blankToNull(request.idempotencyKey()), now);

        debited.applyMovement(EntryDirection.DEBIT.sign(amount));
        credited.applyMovement(EntryDirection.CREDIT.sign(amount));

        transaction.addEntry(new LedgerEntry(
                transaction, debited, EntryDirection.DEBIT, amount, debited.getBalance(), now));
        transaction.addEntry(new LedgerEntry(
                transaction, credited, EntryDirection.CREDIT, amount, credited.getBalance(), now));

        // Flushed inside the transaction so an optimistic lock conflict surfaces here,
        // as a rollback of the whole unit, rather than at some later commit point.
        Transaction saved = transactionRepository.saveAndFlush(transaction);

        BigDecimal reportedBalance = reportedAccountId.equals(debited.getId())
                ? debited.getBalance()
                : credited.getBalance();

        return TransactionResponse.of(saved, reportedAccountId, reportedBalance);
    }

    /**
     * Returns the original result when this idempotency key has already been applied.
     *
     * <p>This is what makes retrying after a 409 safe: without it, a client following
     * the documented "conflict, try again" advice could move money twice.
     */
    private Optional<TransactionResponse> replayIfAlreadyApplied(
            MoneyMovementRequest request, UUID reportedAccountId) {

        String key = blankToNull(request.idempotencyKey());
        if (key == null) {
            return Optional.empty();
        }

        return transactionRepository.findByIdempotencyKey(key)
                .map(existing -> TransactionResponse.of(
                        existing,
                        reportedAccountId,
                        accountRepository.findById(reportedAccountId)
                                .map(Account::getBalance)
                                .orElse(BigDecimal.ZERO)));
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
                .orElseThrow(() -> new IllegalStateException("Account not found: " + accountId));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
