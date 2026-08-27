package com.anas.ledgerwallet.ledger;

import com.anas.ledgerwallet.common.dto.PageResponse;
import com.anas.ledgerwallet.common.security.AuthenticatedUser;
import com.anas.ledgerwallet.ledger.dto.StatementEntryResponse;
import com.anas.ledgerwallet.ledger.dto.TransactionDetailResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only views of the ledger: an account's statement, and a single transaction.
 *
 * <p>No class-level mapping, because the two endpoints sit under different roots — one
 * is scoped to an account, the other addresses a transaction directly.
 */
@RestController
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    /**
     * @param from optional ISO-8601 instant, inclusive (e.g. 2026-08-01T00:00:00Z)
     * @param to optional ISO-8601 instant, inclusive
     */
    @GetMapping("/api/v1/accounts/{id}/transactions")
    public ResponseEntity<PageResponse<StatementEntryResponse>> statement(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(
                statementService.getStatement(id, caller.getId(), page, size, from, to));
    }

    @GetMapping("/api/v1/transactions/{id}")
    public ResponseEntity<TransactionDetailResponse> transaction(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(statementService.getTransaction(id, caller.getId()));
    }
}
