package com.anas.ledgerwallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anas.ledgerwallet.account.Account;
import com.anas.ledgerwallet.account.AccountService;
import com.anas.ledgerwallet.auth.User;
import com.anas.ledgerwallet.common.dto.PageResponse;
import com.anas.ledgerwallet.ledger.dto.StatementEntryResponse;
import com.anas.ledgerwallet.ledger.dto.TransactionDetailResponse;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatementServiceTest {

    @Mock private LedgerEntryRepository ledgerEntryRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountService accountService;

    @InjectMocks private StatementService statementService;

    private static final UUID CALLER_ID = UUID.randomUUID();

    private static User userWithId(UUID id) {
        User user = new User("user@example.com", "hash", "Test User", Instant.now());
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Account account(UUID ownerId, String number) {
        Account account = new Account(userWithId(ownerId), number, Instant.now());
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(account, "balance", new BigDecimal("100.00"));
        return account;
    }

    private static Account systemAccount() {
        Account account = new Account(null, "ACC-SYSTEM", Instant.now());
        ReflectionTestUtils.setField(account, "id", LedgerService.SYSTEM_ACCOUNT_ID);
        ReflectionTestUtils.setField(account, "system", true);
        ReflectionTestUtils.setField(account, "balance", BigDecimal.ZERO);
        return account;
    }

    private static Transaction transaction(
            TransactionType type, Account from, Account to, String amount) {

        Transaction transaction = new Transaction(
                type, new BigDecimal(amount), from, to, UUID.randomUUID(), null, Instant.now());
        ReflectionTestUtils.setField(transaction, "id", UUID.randomUUID());
        return transaction;
    }

    private static LedgerEntry entry(
            Transaction transaction, Account account, EntryDirection direction, String amount) {

        LedgerEntry entry = new LedgerEntry(transaction, account, direction,
                new BigDecimal(amount), new BigDecimal("100.00"), Instant.now());
        ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
        transaction.addEntry(entry);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private void stubPage(List<LedgerEntry> entries) {
        when(ledgerEntryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenAnswer(call -> new PageImpl<>(
                        entries, call.getArgument(1), entries.size()));
    }

    @Test
    @DisplayName("Ownership is checked before any history is read")
    void checksOwnershipFirst() {
        UUID accountId = UUID.randomUUID();
        when(accountService.loadOwnedAccount(accountId, CALLER_ID))
                .thenThrow(new AccessDeniedException("not yours"));

        assertThatThrownBy(() ->
                statementService.getStatement(accountId, CALLER_ID, 0, 20, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("Results are ordered newest first")
    void ordersNewestFirst() {
        UUID accountId = UUID.randomUUID();
        stubPage(List.of());

        statementService.getStatement(accountId, CALLER_ID, 0, 20, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(ledgerEntryRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("Page size is capped so one request cannot ask for the whole ledger")
    void capsPageSize() {
        stubPage(List.of());

        statementService.getStatement(UUID.randomUUID(), CALLER_ID, 0, 100_000, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(ledgerEntryRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(StatementService.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("A nonsensical page or size is coerced rather than rejected")
    void coercesInvalidPaging() {
        stubPage(List.of());

        statementService.getStatement(UUID.randomUUID(), CALLER_ID, -5, 0, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(ledgerEntryRepository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("A transfer entry names the other account as counterparty")
    void reportsCounterpartyForTransfer() {
        Account mine = account(CALLER_ID, "ACC-MINE0000000001");
        Account theirs = account(UUID.randomUUID(), "ACC-THEIRS000000001");
        Transaction transfer = transaction(TransactionType.TRANSFER, mine, theirs, "30.00");
        stubPage(List.of(entry(transfer, mine, EntryDirection.DEBIT, "30.00")));

        PageResponse<StatementEntryResponse> statement =
                statementService.getStatement(mine.getId(), CALLER_ID, 0, 20, null, null);

        StatementEntryResponse row = statement.content().get(0);
        assertThat(row.direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(row.counterparty()).isNotNull();
        assertThat(row.counterparty().accountNumber()).isEqualTo("ACC-THEIRS000000001");
    }

    @Test
    @DisplayName("A deposit reports no counterparty rather than naming the system account")
    void hidesSystemAccountAsCounterparty() {
        Account mine = account(CALLER_ID, "ACC-MINE0000000001");
        Transaction deposit =
                transaction(TransactionType.DEPOSIT, systemAccount(), mine, "50.00");
        stubPage(List.of(entry(deposit, mine, EntryDirection.CREDIT, "50.00")));

        PageResponse<StatementEntryResponse> statement =
                statementService.getStatement(mine.getId(), CALLER_ID, 0, 20, null, null);

        // Money came from outside the system; the internal counterparty is not the
        // caller's business and naming it would invite them to address it.
        assertThat(statement.content().get(0).counterparty()).isNull();
    }

    @Test
    @DisplayName("Either party to a transfer may read the transaction")
    void bothPartiesCanReadTransaction() {
        Account sender = account(CALLER_ID, "ACC-SENDER000000001");
        Account recipient = account(UUID.randomUUID(), "ACC-RECIP0000000001");
        Transaction transfer =
                transaction(TransactionType.TRANSFER, sender, recipient, "30.00");
        entry(transfer, sender, EntryDirection.DEBIT, "30.00");
        entry(transfer, recipient, EntryDirection.CREDIT, "30.00");
        when(transactionRepository.findByIdWithEntries(transfer.getId()))
                .thenReturn(Optional.of(transfer));

        TransactionDetailResponse detail =
                statementService.getTransaction(transfer.getId(), CALLER_ID);

        assertThat(detail.entries()).hasSize(2);
    }

    @Test
    @DisplayName("A stranger to the transaction is refused")
    void rejectsNonParty() {
        Account sender = account(UUID.randomUUID(), "ACC-SENDER000000001");
        Account recipient = account(UUID.randomUUID(), "ACC-RECIP0000000001");
        Transaction transfer =
                transaction(TransactionType.TRANSFER, sender, recipient, "30.00");
        when(transactionRepository.findByIdWithEntries(transfer.getId()))
                .thenReturn(Optional.of(transfer));

        assertThatThrownBy(() ->
                statementService.getTransaction(transfer.getId(), CALLER_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("An unknown transaction id is reported as not found")
    void rejectsUnknownTransaction() {
        UUID unknown = UUID.randomUUID();
        when(transactionRepository.findByIdWithEntries(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> statementService.getTransaction(unknown, CALLER_ID))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    @DisplayName("The transaction detail never carries a recorded balance")
    void detailOmitsBalances() {
        Account sender = account(CALLER_ID, "ACC-SENDER000000001");
        Account recipient = account(UUID.randomUUID(), "ACC-RECIP0000000001");
        Transaction transfer =
                transaction(TransactionType.TRANSFER, sender, recipient, "30.00");
        entry(transfer, sender, EntryDirection.DEBIT, "30.00");
        entry(transfer, recipient, EntryDirection.CREDIT, "30.00");
        when(transactionRepository.findByIdWithEntries(transfer.getId()))
                .thenReturn(Optional.of(transfer));

        TransactionDetailResponse detail =
                statementService.getTransaction(transfer.getId(), CALLER_ID);

        // Both parties can reach this record, so publishing balanceAfter would hand
        // each of them the other's balance at that moment.
        assertThat(detail.toString()).doesNotContain("balanceAfter");
    }

    @Test
    @DisplayName("A page reports total count and whether more pages follow")
    void reportsPagingMetadata() {
        Account mine = account(CALLER_ID, "ACC-MINE0000000001");
        Transaction deposit =
                transaction(TransactionType.DEPOSIT, systemAccount(), mine, "50.00");
        LedgerEntry only = entry(deposit, mine, EntryDirection.CREDIT, "50.00");

        Page<LedgerEntry> page = new PageImpl<>(
                List.of(only),
                org.springframework.data.domain.PageRequest.of(0, 1),
                5);
        when(ledgerEntryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);

        PageResponse<StatementEntryResponse> statement =
                statementService.getStatement(mine.getId(), CALLER_ID, 0, 1, null, null);

        assertThat(statement.totalElements()).isEqualTo(5);
        assertThat(statement.hasNext()).isTrue();
    }
}
