package com.anas.ledgerwallet.ledger;

import com.anas.ledgerwallet.ledger.dto.TransferRequest;
import com.anas.ledgerwallet.ledger.dto.TransferResponse;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * Retry boundary around transfers.
 *
 * <p>A transfer touches two accounts, so it has twice the chance of losing an
 * optimistic lock race, and unlike a deposit the contention is between real users.
 * Retrying a handful of times absorbs the ordinary case where two people happen to
 * touch the same account at the same moment, instead of handing the caller a 409 for
 * something that would succeed a few milliseconds later.
 *
 * <p><strong>Why this is a separate bean.</strong> The retry has to sit outside the
 * transaction: once a transaction is marked rollback-only, retrying inside it changes
 * nothing. Spring's proxying means a self-invocation would skip the advice entirely,
 * so the retryable method has to call across a bean boundary into the transactional
 * one. That is the whole reason this class exists rather than another annotation on
 * {@link LedgerService}.
 *
 * <p>Retrying is only safe because of the idempotency key: if an attempt actually
 * committed and the failure came later, the retry replays the original result rather
 * than moving the money again.
 */
@Service
public class TransferService {

    private final LedgerService ledgerService;

    public TransferService(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /**
     * @throws OptimisticLockingFailureException if every attempt loses its race; the
     *     handler turns that into a 409 and the client may retry with the same key
     */
    @Retryable(
            // Both kinds of contention failure, not just the optimistic one.
            // CannotAcquireLockException covers a database-level deadlock or lock
            // timeout. Ordered locking in LedgerService should stop those arising at
            // all; this is the belt to that pair of braces, so a future code path that
            // takes locks in a new order degrades into a retry rather than a 500.
            retryFor = {
                OptimisticLockingFailureException.class,
                CannotAcquireLockException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2.0, random = true))
    public TransferResponse transfer(TransferRequest request, UUID callerId) {
        return ledgerService.transfer(
                request.fromAccountId(),
                request.toAccountId(),
                callerId,
                request.amount(),
                request.idempotencyKey());
    }
}
