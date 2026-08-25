package com.anas.ledgerwallet.account;

import com.anas.ledgerwallet.account.dto.AccountResponse;
import com.anas.ledgerwallet.auth.User;
import com.anas.ledgerwallet.auth.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account creation and read access. No money movement — that starts in Phase 4. */
@Service
public class AccountService {

    private static final String ACCOUNT_NUMBER_PREFIX = "ACC-";

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public AccountService(AccountRepository accountRepository, UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public AccountResponse createAccount(UUID ownerId) {
        // A reference rather than a full load: only the foreign key is needed, and
        // the owner is known to exist because the request authenticated as them.
        User owner = userRepository.getReferenceById(ownerId);

        Account account = new Account(owner, generateAccountNumber(), Instant.now());

        return AccountResponse.from(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listAccounts(UUID ownerId) {
        // Scoped in the query itself. Fetching all accounts and filtering afterwards
        // would put another user's rows in memory one bug away from being returned.
        return accountRepository.findByOwnerIdOrderByCreatedAtAsc(ownerId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(UUID accountId, UUID callerId) {
        return AccountResponse.from(loadOwnedAccount(accountId, callerId));
    }

    /**
     * Loads an account, refusing it if the caller does not own it.
     *
     * <p>The check lives here rather than in the controller so it cannot be bypassed
     * by a second caller of the same method — every phase that touches an account goes
     * through this one door (rules.md 2.1).
     *
     * <p>Returns 403 rather than 404 for someone else's account, which does confirm
     * that the id exists. That is an accepted trade: account ids are random UUIDs
     * rather than sequential numbers, so there is nothing to enumerate, and the
     * clearer status is what architecture.md documents.
     */
    Account loadOwnedAccount(UUID accountId, UUID callerId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        if (!account.isOwnedBy(callerId)) {
            throw new AccessDeniedException("Account does not belong to the caller");
        }
        return account;
    }

    /**
     * A random, non-sequential identifier.
     *
     * <p>Sequential numbers would leak how many accounts the system holds and let
     * anyone guess a neighbouring one. The uniqueness check is belt-and-braces over
     * the unique index — a collision across 64 bits of randomness is not a practical
     * concern, but a duplicate must never reach the database silently.
     */
    private String generateAccountNumber() {
        String candidate;
        do {
            candidate = ACCOUNT_NUMBER_PREFIX
                    + UUID.randomUUID().toString()
                            .replace("-", "")
                            .substring(0, 16)
                            .toUpperCase(Locale.ROOT);
        } while (accountRepository.existsByAccountNumber(candidate));

        return candidate;
    }
}
