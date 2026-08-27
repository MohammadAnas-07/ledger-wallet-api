package com.anas.ledgerwallet.ledger;

import com.anas.ledgerwallet.common.security.AuthenticatedUser;
import com.anas.ledgerwallet.ledger.dto.TransferRequest;
import com.anas.ledgerwallet.ledger.dto.TransferResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transfers between accounts.
 *
 * <p>The caller supplies both account ids; the service decides whether they may debit
 * the source. Ownership of the destination is never required — sending money to
 * another user is the point.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transferService.transfer(request, caller.getId()));
    }
}
