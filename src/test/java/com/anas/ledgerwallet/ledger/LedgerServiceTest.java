package com.anas.ledgerwallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anas.ledgerwallet.account.Account;
import com.anas.ledgerwallet.account.AccountRepository;
import com.anas.ledgerwallet.account.AccountService;
import com.anas.ledgerwallet.auth.User;
import com.anas.ledgerwallet.ledger.dto.MoneyMovementRequest;
import com.anas.ledgerwallet.ledger.dto.TransactionResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LedgerServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private AccountService accountService;
    @Mock private TransactionRepository transactionRepository;

    @InjectMocks private LedgerService ledgerService;

    private static final UUID CALLER_ID = UUID.randomUUID();

    private static User userWithId(UUID id) {
        User user = new User("user@example.com", "hash", "Test User", Instant.now());
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Account account(UUID id, UUID ownerId, String balance) {
        Account account = new Account(userWithId(ownerId), "ACC-TEST0000000001", Instant.now());
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "balance", new BigDecimal(balance));
        return account;
    }

    private static Account systemAccount() {
        Account account = new Account(null, "ACC-SYSTEM", Instant.now());
        ReflectionTestUtils.setField(account, "id", LedgerService.SYSTEM_ACCOUNT_ID);
        ReflectionTestUtils.setField(account, "system", true);
        ReflectionTestUtils.setField(account, "balance", new BigDecimal("0.00"));
        return account;
    }

    private void wire(Account userAccount) {
        Account system = systemAccount();
        when(accountRepository.findById(userAccount.getId())).thenReturn(Optional.of(userAccount));
        when(accountRepository.findById(LedgerService.SYSTEM_ACCOUNT_ID))
                .thenReturn(Optional.of(system));
        when(accountService.loadOwnedAccount(userAccount.getId(), CALLER_ID))
                .thenReturn(userAccount);
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    private static MoneyMovementRequest amount(String value) {
        return new MoneyMovementRequest(new BigDecimal(value), null);
    }

    @Test
    @DisplayName("A deposit credits the account and raises the balance")
    void depositCreditsAccount() {
        Account account = account(UUID.randomUUID(), CALLER_ID, "100.00");
        wire(account);

        TransactionResponse response =
                ledgerService.deposit(account.getId(), CALLER_ID, amount("40.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("140.00");
        assertThat(response.balanceAfter()).isEqualByComparingTo("140.00");
        assertThat(response.type()).isEqualTo(TransactionType.DEPOSIT);
    }

    @Test
    @DisplayName("A withdrawal debits the account and lowers the balance")
    void withdrawDebitsAccount() {
        Account account = account(UUID.randomUUID(), CALLER_ID, "100.00");
        wire(account);

        TransactionResponse response =
                ledgerService.withdraw(account.getId(), CALLER_ID, amount("30.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("70.00");
        assertThat(response.balanceAfter()).isEqualByComparingTo("70.00");
        assertThat(response.type()).isEqualTo(TransactionType.WITHDRAWAL);
    }

    @Test
    @DisplayName("Every movement writes exactly one debit and one matching credit")
    void writesBalancedDoubleEntry() {
        Account account = account(UUID.randomUUID(), CALLER_ID, "100.00");
        wire(account);

        ledgerService.deposit(account.getId(), CALLER_ID, amount("25.00"));

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(saved.capture());

        assertThat(saved.getValue().getEntries()).hasSize(2);
        assertThat(saved.getValue().getEntries())
                .extracting(LedgerEntry::getDirection)
                .containsExactlyInAnyOrder(EntryDirection.DEBIT, EntryDirection.CREDIT);

        // The invariant, at the level of a single transaction: the two signed amounts
        // cancel out, so the system total is unchanged by this write.
        BigDecimal sum = saved.getValue().getEntries().stream()
                .map(LedgerEntry::getSignedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("A withdrawal beyond the balance is refused and writes nothing")
    void rejectsInsufficientFunds() {
        Account account = account(UUID.randomUUID(), CALLER_ID, "50.00");
        wire(account);

        assertThatThrownBy(() ->
                ledgerService.withdraw(account.getId(), CALLER_ID, amount("50.01")))
                .isInstanceOf(InsufficientFundsException.class);

        // Nothing written and nothing moved: a refused withdrawal must leave no trace.
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        assertThat(account.getBalance()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Withdrawing the entire balance is allowed, down to exactly zero")
    void allowsWithdrawingFullBalance() {
        Account account = account(UUID.randomUUID(), CALLER_ID, "50.00");
        wire(account);

        ledgerService.withdraw(account.getId(), CALLER_ID, amount("50.00"));

        // The boundary belongs to the user: >= not >.
        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Depositing into someone else's account is refused")
    void rejectsForeignAccount() {
        Account account = account(UUID.randomUUID(), UUID.randomUUID(), "100.00");
        UUID intruderId = UUID.randomUUID();
        when(accountService.loadOwnedAccount(account.getId(), intruderId))
                .thenThrow(new AccessDeniedException("not yours"));

        assertThatThrownBy(() ->
                ledgerService.deposit(account.getId(), intruderId, amount("10.00")))
                .isInstanceOf(AccessDeniedException.class);

        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    @Test
    @DisplayName("Ownership is checked before funds, so a refusal reveals no balance")
    void checksOwnershipBeforeFunds() {
        Account account = account(UUID.randomUUID(), UUID.randomUUID(), "0.00");
        UUID intruderId = UUID.randomUUID();
        when(accountService.loadOwnedAccount(account.getId(), intruderId))
                .thenThrow(new AccessDeniedException("not yours"));

        // An intruder withdrawing from an empty account must get 403, not 422 —
        // otherwise the error code itself reports whether the account has money.
        assertThatThrownBy(() ->
                ledgerService.withdraw(account.getId(), intruderId, amount("10.00")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("A repeated idempotency key replays the original result")
    void replaysOnIdempotencyKey() {
        Account account = account(UUID.randomUUID(), CALLER_ID, "100.00");
        wire(account);

        Transaction existing = new Transaction(
                TransactionType.DEPOSIT,
                new BigDecimal("40.00"),
                systemAccount(),
                account,
                "retry-key-1",
                Instant.now());
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(transactionRepository.findByIdempotencyKey("retry-key-1"))
                .thenReturn(Optional.of(existing));

        TransactionResponse response = ledgerService.deposit(
                account.getId(),
                CALLER_ID,
                new MoneyMovementRequest(new BigDecimal("40.00"), "retry-key-1"));

        // The retry returns the original transaction and moves no money a second time.
        assertThat(response.transactionId()).isEqualTo(existing.getId());
        assertThat(account.getBalance()).isEqualByComparingTo("100.00");
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    @Test
    @DisplayName("The system account absorbs the other side of every movement")
    void systemAccountTakesCounterEntry() {
        Account account = account(UUID.randomUUID(), CALLER_ID, "100.00");
        wire(account);

        ledgerService.deposit(account.getId(), CALLER_ID, amount("40.00"));

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFromAccount().getId())
                .isEqualTo(LedgerService.SYSTEM_ACCOUNT_ID);
        // Money entering the system leaves the system account, so it runs negative.
        assertThat(saved.getValue().getFromAccount().getBalance())
                .isEqualByComparingTo("-40.00");
    }
}
