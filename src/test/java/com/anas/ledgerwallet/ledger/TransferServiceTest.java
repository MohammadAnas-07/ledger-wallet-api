package com.anas.ledgerwallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anas.ledgerwallet.account.Account;
import com.anas.ledgerwallet.account.AccountNotFoundException;
import com.anas.ledgerwallet.account.AccountRepository;
import com.anas.ledgerwallet.account.AccountService;
import com.anas.ledgerwallet.auth.User;
import com.anas.ledgerwallet.ledger.dto.TransferResponse;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/** Transfer rules, exercised directly against {@link LedgerService}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferServiceTest {

    @Mock private AccountRepository accountRepository;
    @Mock private AccountService accountService;
    @Mock private TransactionRepository transactionRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private LedgerService ledgerService;

    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID RECIPIENT_ID = UUID.randomUUID();

    private static User userWithId(UUID id) {
        User user = new User("user@example.com", "hash", "Test User", Instant.now());
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Account account(UUID ownerId, String balance) {
        Account account = new Account(userWithId(ownerId), "ACC-TEST0000000001", Instant.now());
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
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

    private void wire(Account source, Account destination) {
        when(accountRepository.findById(source.getId())).thenReturn(Optional.of(source));
        when(accountRepository.findById(destination.getId()))
                .thenReturn(Optional.of(destination));
        when(accountService.loadOwnedAccount(source.getId(), SENDER_ID)).thenReturn(source);
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("A transfer debits the sender and credits the recipient")
    void movesMoneyBetweenAccounts() {
        Account source = account(SENDER_ID, "100.00");
        Account destination = account(RECIPIENT_ID, "20.00");
        wire(source, destination);

        TransferResponse response = ledgerService.transfer(
                source.getId(), destination.getId(), SENDER_ID, new BigDecimal("30.00"), null);

        assertThat(source.getBalance()).isEqualByComparingTo("70.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("50.00");
        assertThat(response.fromBalanceAfter()).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("Total money is unchanged by a transfer")
    void conservesTotalMoney() {
        Account source = account(SENDER_ID, "100.00");
        Account destination = account(RECIPIENT_ID, "20.00");
        wire(source, destination);

        BigDecimal before = source.getBalance().add(destination.getBalance());
        ledgerService.transfer(
                source.getId(), destination.getId(), SENDER_ID, new BigDecimal("30.00"), null);

        // A transfer moves money; it must never create or destroy any.
        assertThat(source.getBalance().add(destination.getBalance()))
                .isEqualByComparingTo(before);
    }

    @Test
    @DisplayName("A transfer writes one debit and one matching credit that cancel out")
    void writesBalancedDoubleEntry() {
        Account source = account(SENDER_ID, "100.00");
        Account destination = account(RECIPIENT_ID, "0.00");
        wire(source, destination);

        ledgerService.transfer(
                source.getId(), destination.getId(), SENDER_ID, new BigDecimal("40.00"), null);

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(saved.capture());

        assertThat(saved.getValue().getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(saved.getValue().getEntries()).hasSize(2);
        assertThat(saved.getValue().getEntries())
                .extracting(LedgerEntry::getDirection)
                .containsExactlyInAnyOrder(EntryDirection.DEBIT, EntryDirection.CREDIT);

        BigDecimal sum = saved.getValue().getEntries().stream()
                .map(LedgerEntry::getSignedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Transferring to yourself is rejected before anything is read")
    void rejectsSelfTransfer() {
        UUID accountId = UUID.randomUUID();

        assertThatThrownBy(() -> ledgerService.transfer(
                accountId, accountId, SENDER_ID, new BigDecimal("10.00"), null))
                .isInstanceOf(SelfTransferException.class);

        // Rejected before the ownership check even runs: nothing was loaded.
        verify(accountRepository, never()).findById(any(UUID.class));
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    @Test
    @DisplayName("A transfer beyond the balance is refused and writes nothing")
    void rejectsInsufficientFunds() {
        Account source = account(SENDER_ID, "25.00");
        Account destination = account(RECIPIENT_ID, "0.00");
        wire(source, destination);

        assertThatThrownBy(() -> ledgerService.transfer(
                source.getId(), destination.getId(), SENDER_ID, new BigDecimal("25.01"), null))
                .isInstanceOf(InsufficientFundsException.class);

        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
        assertThat(source.getBalance()).isEqualByComparingTo("25.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Sending from an account you do not own is refused")
    void rejectsUnownedSource() {
        Account source = account(UUID.randomUUID(), "100.00");
        Account destination = account(RECIPIENT_ID, "0.00");
        when(accountRepository.findById(destination.getId()))
                .thenReturn(Optional.of(destination));
        UUID intruderId = UUID.randomUUID();
        when(accountService.loadOwnedAccount(source.getId(), intruderId))
                .thenThrow(new AccessDeniedException("not yours"));

        assertThatThrownBy(() -> ledgerService.transfer(
                source.getId(), destination.getId(), intruderId, new BigDecimal("10.00"), null))
                .isInstanceOf(AccessDeniedException.class);

        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    @Test
    @DisplayName("Sending to an account you do not own is allowed")
    void allowsCreditingAnotherUser() {
        Account source = account(SENDER_ID, "100.00");
        // Owned by a different user entirely.
        Account destination = account(UUID.randomUUID(), "0.00");
        wire(source, destination);

        ledgerService.transfer(
                source.getId(), destination.getId(), SENDER_ID, new BigDecimal("10.00"), null);

        // You may credit anyone; you may only debit yourself (architecture.md 5).
        assertThat(destination.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("An unknown destination is reported as not found")
    void rejectsUnknownDestination() {
        Account source = account(SENDER_ID, "100.00");
        UUID unknown = UUID.randomUUID();
        when(accountRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ledgerService.transfer(
                source.getId(), unknown, SENDER_ID, new BigDecimal("10.00"), null))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    @DisplayName("The system account is not a valid destination")
    void rejectsSystemAccountAsDestination() {
        Account source = account(SENDER_ID, "100.00");
        Account system = systemAccount();
        when(accountRepository.findById(system.getId())).thenReturn(Optional.of(system));

        // It exists in the table, but it is internal plumbing. Reported as not found
        // so the API surface does not admit it is there at all.
        assertThatThrownBy(() -> ledgerService.transfer(
                source.getId(), system.getId(), SENDER_ID, new BigDecimal("10.00"), null))
                .isInstanceOf(AccountNotFoundException.class);

        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    @Test
    @DisplayName("A repeated idempotency key replays instead of moving money again")
    void replaysOnIdempotencyKey() {
        Account source = account(SENDER_ID, "100.00");
        Account destination = account(RECIPIENT_ID, "0.00");
        wire(source, destination);

        Transaction existing = new Transaction(
                TransactionType.TRANSFER, new BigDecimal("30.00"), source, destination,
                "transfer-key-1", Instant.now());
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        when(transactionRepository.findByIdempotencyKey("transfer-key-1"))
                .thenReturn(Optional.of(existing));

        TransferResponse response = ledgerService.transfer(
                source.getId(), destination.getId(), SENDER_ID,
                new BigDecimal("30.00"), "transfer-key-1");

        // This is what makes the server's own retry safe to run.
        assertThat(response.transactionId()).isEqualTo(existing.getId());
        assertThat(source.getBalance()).isEqualByComparingTo("100.00");
        assertThat(destination.getBalance()).isEqualByComparingTo("0.00");
        verify(transactionRepository, never()).saveAndFlush(any(Transaction.class));
    }

    @Test
    @DisplayName("The response does not reveal the recipient's balance")
    void hidesRecipientBalance() {
        Account source = account(SENDER_ID, "100.00");
        Account destination = account(RECIPIENT_ID, "5000.00");
        wire(source, destination);

        TransferResponse response = ledgerService.transfer(
                source.getId(), destination.getId(), SENDER_ID, new BigDecimal("10.00"), null);

        // Otherwise repeated small transfers would turn this endpoint into a balance
        // oracle for any account whose id is known.
        assertThat(response.toString()).doesNotContain("5010.00");
        assertThat(response.fromBalanceAfter()).isEqualByComparingTo("90.00");
    }
}
