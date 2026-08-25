package com.anas.ledgerwallet.account;

import com.anas.ledgerwallet.account.dto.AccountResponse;
import com.anas.ledgerwallet.common.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wallets belonging to the authenticated caller.
 *
 * <p>Every method takes the caller from the {@code SecurityContext}. No endpoint here
 * accepts an owner id from the client — one that did would let any authenticated user
 * name someone else and act as them (rules.md 2.1).
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** Creates a wallet for the caller. No request body: an account has no inputs yet. */
    @PostMapping
    public ResponseEntity<AccountResponse> create(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.createAccount(caller.getId()));
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser caller) {

        return ResponseEntity.ok(accountService.listAccounts(caller.getId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> get(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUser caller) {

        // The id names which account to read; whether the caller may read it is
        // decided in the service, against the authenticated identity.
        return ResponseEntity.ok(accountService.getAccount(id, caller.getId()));
    }
}
