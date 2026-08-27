package com.anas.ledgerwallet.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.anas.ledgerwallet.IntegrationTestBase;
import com.anas.ledgerwallet.account.AccountRepository;
import com.anas.ledgerwallet.account.dto.AccountResponse;
import com.anas.ledgerwallet.auth.dto.AuthResponse;
import com.anas.ledgerwallet.auth.dto.LoginRequest;
import com.anas.ledgerwallet.auth.dto.RegisterRequest;
import com.anas.ledgerwallet.auth.dto.UserResponse;
import com.anas.ledgerwallet.common.dto.PageResponse;
import com.anas.ledgerwallet.common.error.ErrorResponse;
import com.anas.ledgerwallet.ledger.dto.MoneyMovementRequest;
import com.anas.ledgerwallet.ledger.dto.StatementEntryResponse;
import com.anas.ledgerwallet.ledger.dto.TransactionDetailResponse;
import com.anas.ledgerwallet.ledger.dto.TransactionResponse;
import com.anas.ledgerwallet.ledger.dto.TransferRequest;
import com.anas.ledgerwallet.ledger.dto.TransferResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Statement and transaction-detail endpoints end to end. */
class StatementIT extends IntegrationTestBase {

    private static final String PASSWORD = "a-sufficiently-long-password";
    private static final ParameterizedTypeReference<PageResponse<StatementEntryResponse>>
            STATEMENT_PAGE = new ParameterizedTypeReference<>() {};

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;

    private String newUserToken() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        restTemplate.postForEntity("/api/v1/auth/register",
                new RegisterRequest(email, PASSWORD, "Test User"), UserResponse.class);
        return restTemplate.postForEntity("/api/v1/auth/login",
                new LoginRequest(email, PASSWORD), AuthResponse.class).getBody().accessToken();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private UUID newAccount(String token) {
        return restTemplate.exchange("/api/v1/accounts", HttpMethod.POST,
                new HttpEntity<>(bearer(token)), AccountResponse.class).getBody().id();
    }

    private void deposit(String token, UUID accountId, String amount) {
        restTemplate.exchange("/api/v1/accounts/" + accountId + "/deposit", HttpMethod.POST,
                new HttpEntity<>(new MoneyMovementRequest(new BigDecimal(amount), null),
                        bearer(token)),
                TransactionResponse.class);
    }

    private void withdraw(String token, UUID accountId, String amount) {
        restTemplate.exchange("/api/v1/accounts/" + accountId + "/withdraw", HttpMethod.POST,
                new HttpEntity<>(new MoneyMovementRequest(new BigDecimal(amount), null),
                        bearer(token)),
                TransactionResponse.class);
    }

    private UUID transfer(String token, UUID from, UUID to, String amount) {
        return restTemplate.exchange("/api/v1/transfers", HttpMethod.POST,
                new HttpEntity<>(new TransferRequest(from, to, new BigDecimal(amount), null),
                        bearer(token)),
                TransferResponse.class).getBody().transactionId();
    }

    private ResponseEntity<PageResponse<StatementEntryResponse>> statement(
            String token, UUID accountId, String query) {

        return restTemplate.exchange(
                "/api/v1/accounts/" + accountId + "/transactions" + query,
                HttpMethod.GET, new HttpEntity<>(bearer(token)), STATEMENT_PAGE);
    }

    @Test
    @DisplayName("A statement lists the account's own movements, newest first")
    void listsOwnHistoryNewestFirst() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        deposit(token, accountId, "100.00");
        withdraw(token, accountId, "30.00");

        ResponseEntity<PageResponse<StatementEntryResponse>> response =
                statement(token, accountId, "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<StatementEntryResponse> rows = response.getBody().content();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).type()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(rows.get(0).direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(rows.get(1).type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(rows.get(1).direction()).isEqualTo(EntryDirection.CREDIT);
    }

    @Test
    @DisplayName("The statement reconciles with the stored balance")
    void statementReconcilesWithBalance() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        deposit(token, accountId, "250.00");
        withdraw(token, accountId, "75.00");

        BigDecimal fromStatement = statement(token, accountId, "?size=100").getBody().content()
                .stream()
                .map(row -> row.direction() == EntryDirection.DEBIT
                        ? row.amount().negate()
                        : row.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(fromStatement)
                .isEqualByComparingTo(
                        accountRepository.findById(accountId).orElseThrow().getBalance());
        assertThat(ledgerEntryRepository.sumAllSignedAmounts())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("A transfer appears on both statements with opposite directions")
    void transferAppearsOnBothStatements() {
        String senderToken = newUserToken();
        String recipientToken = newUserToken();
        UUID sender = newAccount(senderToken);
        UUID recipient = newAccount(recipientToken);
        deposit(senderToken, sender, "200.00");

        UUID transactionId = transfer(senderToken, sender, recipient, "80.00");

        StatementEntryResponse senderRow = statement(senderToken, sender, "").getBody()
                .content().get(0);
        StatementEntryResponse recipientRow = statement(recipientToken, recipient, "").getBody()
                .content().get(0);

        // The same transaction, seen from two sides.
        assertThat(senderRow.transactionId()).isEqualTo(transactionId);
        assertThat(recipientRow.transactionId()).isEqualTo(transactionId);
        assertThat(senderRow.direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(recipientRow.direction()).isEqualTo(EntryDirection.CREDIT);
        assertThat(senderRow.amount()).isEqualByComparingTo(recipientRow.amount());

        // Each sees the other as counterparty, and their own running balance.
        assertThat(senderRow.counterparty().accountId()).isEqualTo(recipient);
        assertThat(recipientRow.counterparty().accountId()).isEqualTo(sender);
        assertThat(senderRow.balanceAfter()).isEqualByComparingTo("120.00");
        assertThat(recipientRow.balanceAfter()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("A deposit shows no counterparty")
    void depositHasNoCounterparty() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        deposit(token, accountId, "10.00");

        StatementEntryResponse row = statement(token, accountId, "").getBody().content().get(0);

        assertThat(row.counterparty()).isNull();
    }

    @Test
    @DisplayName("Pagination splits the history and reports totals")
    void paginates() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        for (int i = 0; i < 5; i++) {
            deposit(token, accountId, "10.00");
        }

        PageResponse<StatementEntryResponse> firstPage =
                statement(token, accountId, "?page=0&size=2").getBody();
        PageResponse<StatementEntryResponse> lastPage =
                statement(token, accountId, "?page=2&size=2").getBody();

        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalElements()).isEqualTo(5);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(firstPage.hasNext()).isTrue();

        assertThat(lastPage.content()).hasSize(1);
        assertThat(lastPage.hasNext()).isFalse();

        // Pages must not overlap.
        assertThat(firstPage.content().get(0).entryId())
                .isNotEqualTo(lastPage.content().get(0).entryId());
    }

    @Test
    @DisplayName("An oversized page request is capped rather than honoured")
    void capsPageSize() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        deposit(token, accountId, "10.00");

        PageResponse<StatementEntryResponse> page =
                statement(token, accountId, "?size=100000").getBody();

        assertThat(page.size()).isEqualTo(100);
    }

    @Test
    @DisplayName("A date range filters the statement")
    void filtersByDateRange() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        deposit(token, accountId, "10.00");

        Instant future = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);

        assertThat(statement(token, accountId, "?from=" + future).getBody().content())
                .as("nothing was recorded after tomorrow")
                .isEmpty();
        assertThat(statement(token, accountId, "?to=" + past).getBody().content())
                .as("nothing was recorded before yesterday")
                .isEmpty();
        assertThat(statement(token, accountId, "?from=" + past + "&to=" + future)
                .getBody().content())
                .hasSize(1);
    }

    @Test
    @DisplayName("Reading another user's statement returns 403")
    void cannotReadAnotherUsersStatement() {
        String ownerToken = newUserToken();
        String intruderToken = newUserToken();
        UUID accountId = newAccount(ownerToken);
        deposit(ownerToken, accountId, "500.00");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/accounts/" + accountId + "/transactions", HttpMethod.GET,
                new HttpEntity<>(bearer(intruderToken)), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("Statement endpoints require authentication")
    void requiresAuthentication() {
        assertThat(restTemplate.getForEntity(
                "/api/v1/accounts/" + UUID.randomUUID() + "/transactions", String.class)
                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity(
                "/api/v1/transactions/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Both parties can read a transfer's detail, showing two balanced entries")
    void bothPartiesCanReadTransactionDetail() {
        String senderToken = newUserToken();
        String recipientToken = newUserToken();
        UUID sender = newAccount(senderToken);
        UUID recipient = newAccount(recipientToken);
        deposit(senderToken, sender, "100.00");
        UUID transactionId = transfer(senderToken, sender, recipient, "40.00");

        for (String token : List.of(senderToken, recipientToken)) {
            ResponseEntity<TransactionDetailResponse> response = restTemplate.exchange(
                    "/api/v1/transactions/" + transactionId, HttpMethod.GET,
                    new HttpEntity<>(bearer(token)), TransactionDetailResponse.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            TransactionDetailResponse detail = response.getBody();
            assertThat(detail.entries()).hasSize(2);

            BigDecimal sum = detail.entries().stream()
                    .map(TransactionDetailResponse.EntryResponse::signedAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("A stranger cannot read a transaction they were not party to")
    void strangerCannotReadTransactionDetail() {
        String senderToken = newUserToken();
        String strangerToken = newUserToken();
        UUID sender = newAccount(senderToken);
        UUID recipient = newAccount(newUserToken());
        deposit(senderToken, sender, "100.00");
        UUID transactionId = transfer(senderToken, sender, recipient, "40.00");

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transactions/" + transactionId, HttpMethod.GET,
                new HttpEntity<>(bearer(strangerToken)), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("A deposit's detail hides the system account behind an external marker")
    void depositDetailHidesSystemAccount() {
        String token = newUserToken();
        UUID accountId = newAccount(token);
        deposit(token, accountId, "60.00");

        UUID transactionId = statement(token, accountId, "").getBody()
                .content().get(0).transactionId();

        TransactionDetailResponse detail = restTemplate.exchange(
                "/api/v1/transactions/" + transactionId, HttpMethod.GET,
                new HttpEntity<>(bearer(token)), TransactionDetailResponse.class).getBody();

        assertThat(detail.entries()).hasSize(2);
        assertThat(detail.entries()).anySatisfy(entry -> {
            assertThat(entry.external()).isTrue();
            assertThat(entry.accountId()).isNull();
            assertThat(entry.accountNumber()).isNull();
        });
    }

    @Test
    @DisplayName("An unknown transaction id returns 404")
    void unknownTransactionReturnsNotFound() {
        String token = newUserToken();

        ResponseEntity<ErrorResponse> response = restTemplate.exchange(
                "/api/v1/transactions/" + UUID.randomUUID(), HttpMethod.GET,
                new HttpEntity<>(bearer(token)), ErrorResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("TRANSACTION_NOT_FOUND");
    }
}
