package com.anas.ledgerwallet.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anas.ledgerwallet.account.dto.AccountResponse;
import com.anas.ledgerwallet.auth.User;
import com.anas.ledgerwallet.auth.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private AccountService accountService;

    /** A user with an id set, which only the persistence layer would normally assign. */
    private static User userWithId(UUID id) {
        User user = new User("user@example.com", "hashed-value", "Test User", Instant.now());
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Account accountOwnedBy(UUID ownerId) {
        Account account = new Account(userWithId(ownerId), "ACC-TESTACCOUNT01", Instant.now());
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }

    @Test
    @DisplayName("A new account starts at zero, active, and owned by the caller")
    void createsAccountWithZeroBalance() {
        UUID ownerId = UUID.randomUUID();
        when(userRepository.getReferenceById(ownerId)).thenReturn(userWithId(ownerId));
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));

        AccountResponse response = accountService.createAccount(ownerId);

        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);

        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertThat(saved.getValue().isOwnedBy(ownerId)).isTrue();
    }

    @Test
    @DisplayName("A new balance carries two decimal places, not a bare zero")
    void createsAccountWithScaledBalance() {
        UUID ownerId = UUID.randomUUID();
        when(userRepository.getReferenceById(ownerId)).thenReturn(userWithId(ownerId));
        when(accountRepository.existsByAccountNumber(anyString())).thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));

        AccountResponse response = accountService.createAccount(ownerId);

        // Money is always scale 2. A balance rendered as "0" rather than "0.00" is the
        // first sign that scale is being lost somewhere in the chain.
        assertThat(response.balance().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("Account numbers are not sequential and are retried on collision")
    void generatesUniqueAccountNumber() {
        UUID ownerId = UUID.randomUUID();
        when(userRepository.getReferenceById(ownerId)).thenReturn(userWithId(ownerId));
        // First candidate collides, so the service must try again rather than save it.
        when(accountRepository.existsByAccountNumber(anyString()))
                .thenReturn(true)
                .thenReturn(false);
        when(accountRepository.save(any(Account.class))).thenAnswer(call -> call.getArgument(0));

        AccountResponse response = accountService.createAccount(ownerId);

        verify(accountRepository, org.mockito.Mockito.times(2)).existsByAccountNumber(anyString());
        assertThat(response.accountNumber()).startsWith("ACC-");
    }

    @Test
    @DisplayName("Listing is scoped to the caller in the query itself")
    void listsOnlyCallerAccounts() {
        UUID ownerId = UUID.randomUUID();
        when(accountRepository.findByOwnerIdOrderByCreatedAtAsc(ownerId))
                .thenReturn(List.of(accountOwnedBy(ownerId)));

        List<AccountResponse> accounts = accountService.listAccounts(ownerId);

        assertThat(accounts).hasSize(1);
        // The owner id reaches the repository: filtering in memory afterwards would
        // mean another user's rows were loaded in the first place.
        verify(accountRepository).findByOwnerIdOrderByCreatedAtAsc(ownerId);
    }

    @Test
    @DisplayName("An owner can read their own account")
    void ownerCanReadOwnAccount() {
        UUID ownerId = UUID.randomUUID();
        Account account = accountOwnedBy(ownerId);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        AccountResponse response = accountService.getAccount(account.getId(), ownerId);

        assertThat(response.id()).isEqualTo(account.getId());
    }

    @Test
    @DisplayName("Reading someone else's account is refused")
    void rejectsForeignAccountAccess() {
        UUID ownerId = UUID.randomUUID();
        UUID intruderId = UUID.randomUUID();
        Account account = accountOwnedBy(ownerId);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        // The core authorisation rule of the whole system: authentication says who is
        // calling, it does not say what they may touch.
        assertThatThrownBy(() -> accountService.getAccount(account.getId(), intruderId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("An unknown account id is reported as not found")
    void rejectsUnknownAccount() {
        UUID unknownId = UUID.randomUUID();
        when(accountRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(unknownId, UUID.randomUUID()))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("The response exposes no lock version")
    void responseHidesVersion() {
        UUID ownerId = UUID.randomUUID();
        Account account = accountOwnedBy(ownerId);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        AccountResponse response = accountService.getAccount(account.getId(), ownerId);

        // version is an internal concurrency detail. Publishing it invites clients to
        // send it back and start reasoning about locking on the wire.
        assertThat(response.toString()).doesNotContain("version");
    }
}
