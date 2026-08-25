package com.anas.ledgerwallet.ledger;

import com.anas.ledgerwallet.common.security.AuthenticatedUser;
import com.anas.ledgerwallet.ledger.dto.MoneyMovementRequest;
import com.anas.ledgerwallet.ledger.dto.TransactionResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deposits and withdrawals against a single account.
 *
 * <p>Both return 201: each one creates a transaction, and the response names it.
 */
@RestController
@RequestMapping("/api/v1/accounts/{id}")
public class MoneyMovementController {

    private final LedgerService ledgerService;

    public MoneyMovementController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable UUID id,
            @Valid @RequestBody MoneyMovementRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ledgerService.deposit(id, caller.getId(), request));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @PathVariable UUID id,
            @Valid @RequestBody MoneyMovementRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ledgerService.withdraw(id, caller.getId(), request));
    }
}
