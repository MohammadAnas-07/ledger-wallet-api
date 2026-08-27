package com.anas.ledgerwallet.ledger;

import com.anas.ledgerwallet.account.AccountService;
import com.anas.ledgerwallet.common.dto.PageResponse;
import com.anas.ledgerwallet.ledger.dto.StatementEntryResponse;
import com.anas.ledgerwallet.ledger.dto.TransactionDetailResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only views over the ledger.
 *
 * <p>Nothing here writes. Ledger entries are append-only: a correction is a new,
 * reversing transaction, never an edit to history (prd.md §3.5).
 */
@Service
public class StatementService {

    /**
     * Upper bound on a page. Without it a caller could ask for the whole ledger in one
     * request and turn a statement into a denial-of-service lever.
     */
    static final int MAX_PAGE_SIZE = 100;

    static final int DEFAULT_PAGE_SIZE = 20;

    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionRepository transactionRepository;
    private final AccountService accountService;

    public StatementService(
            LedgerEntryRepository ledgerEntryRepository,
            TransactionRepository transactionRepository,
            AccountService accountService) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transactionRepository = transactionRepository;
        this.accountService = accountService;
    }

    /**
     * One account's statement, newest first.
     *
     * @param from inclusive lower bound on entry time, or null
     * @param to inclusive upper bound on entry time, or null
     */
    @Transactional(readOnly = true)
    public PageResponse<StatementEntryResponse> getStatement(
            UUID accountId, UUID callerId, int page, int size, Instant from, Instant to) {

        // Ownership before anything is read, so an unauthorised caller learns nothing
        // about whether the account has any history at all.
        accountService.loadOwnedAccount(accountId, callerId);

        Specification<LedgerEntry> criteria = LedgerEntrySpecifications.forAccount(accountId)
                .and(LedgerEntrySpecifications.createdAtOrAfter(from))
                .and(LedgerEntrySpecifications.createdAtOrBefore(to));

        Page<StatementEntryResponse> results =
                ledgerEntryRepository.findAll(criteria, pageRequest(page, size))
                        .map(StatementEntryResponse::from);

        return PageResponse.from(results);
    }

    /**
     * A single transaction with both of its entries.
     *
     * <p>Visible to either party. A transfer has two sides and both of them have a
     * legitimate claim on the record, so ownership of one side is enough — but not
     * ownership of neither.
     */
    @Transactional(readOnly = true)
    public TransactionDetailResponse getTransaction(UUID transactionId, UUID callerId) {
        Transaction transaction = transactionRepository.findByIdWithEntries(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        boolean callerIsParty = transaction.getFromAccount().isOwnedBy(callerId)
                || transaction.getToAccount().isOwnedBy(callerId);

        if (!callerIsParty) {
            throw new AccessDeniedException("Transaction does not involve the caller");
        }
        return TransactionDetailResponse.from(transaction);
    }

    /**
     * Ordering is fixed, not caller-supplied.
     *
     * <p>Accepting a {@code Pageable} straight from the request would let a client sort
     * by any property name it liked, which leaks the entity's shape and is the surface
     * that a sort-field allowlist exists to defend. Not offering the knob removes the
     * problem rather than guarding it.
     */
    private Pageable pageRequest(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);

        return PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    }
}
