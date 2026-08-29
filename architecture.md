# System Architecture — Wallet & Ledger API

**Project:** `ledger-wallet-api`
**Companion document:** [prd.md](prd.md)
**Guiding constraint:** the ledger must balance under concurrent load — every architectural decision below serves that.

---

## 1. High-Level Architecture

```
                             ┌──────────────────────────────┐
   HTTP / JSON               │        CLIENT                │
   Bearer <JWT>              │  (Postman / mobile / web)    │
                             └───────────────┬──────────────┘
                                             │
═════════════════════════════════════════════▼══════════════════════════════════════
                       SPRING BOOT APPLICATION (single deployable)
────────────────────────────────────────────────────────────────────────────────────

  ┌──────────────────────────────────────────────────────────────────────────────┐
  │  SECURITY FILTER CHAIN                                                       │
  │  JwtAuthenticationFilter → validate signature + expiry → set                 │
  │  SecurityContext  ·  401 if absent/invalid                                   │
  └───────────────────────────────────┬──────────────────────────────────────────┘
                                      │
  ┌───────────────────────────────────▼──────────────────────────────────────────┐
  │  API / CONTROLLER LAYER            @RestController                           │
  │  AuthController · AccountController · TransferController · TxHistoryController│
  │  Responsibilities: HTTP mapping, @Valid on request DTOs, DTO↔domain mapping,  │
  │  status codes. NO business logic, NO entities on the wire.                   │
  │  @RestControllerAdvice → uniform error body { code, message, timestamp }      │
  └───────────────────────────────────┬──────────────────────────────────────────┘
                                      │
  ┌───────────────────────────────────▼──────────────────────────────────────────┐
  │  SERVICE LAYER                     @Service   @Transactional                  │
  │  AuthService · AccountService · TransferService · LedgerService               │
  │  Responsibilities: ownership checks, business rules (sufficient funds,        │
  │  positive amount, idempotency), DOUBLE-ENTRY POSTING, transaction boundary,   │
  │  optimistic-lock conflict handling, post-commit event registration.           │
  │  ◄── this is where correctness lives ───────────────────────────────────────► │
  └──────────┬───────────────────────────────────────────────┬───────────────────┘
             │                                               │
  ┌──────────▼───────────────────────────┐   ┌───────────────▼────────────────────┐
  │  REPOSITORY LAYER                    │   │  EVENT PUBLISHER                   │
  │  Spring Data JPA interfaces          │   │  TransactionEventPublisher         │
  │  UserRepository · AccountRepository  │   │  @TransactionalEventListener       │
  │  TransactionRepository               │   │      (phase = AFTER_COMMIT)        │
  │  LedgerEntryRepository               │   │  → KafkaTemplate.send(...)         │
  └──────────┬───────────────────────────┘   └───────────────┬────────────────────┘
             │ Hibernate / JDBC                              │ Kafka producer API
═════════════▼═══════════════════════════════════════════════▼══════════════════════
  ┌──────────────────────────────┐              ┌────────────────────────────────┐
  │      PostgreSQL 16           │              │        Apache Kafka            │
  │  users · accounts            │              │   topic: transaction-events    │
  │  transactions · ledger_entries│             │   (3 partitions, key=accountId)│
  │  ACID · single tx boundary   │              └───────────────┬────────────────┘
  │  accounts.version → OCC      │                              │
  └──────────────────────────────┘              ┌───────────────▼────────────────┐
                                                │   AuditLogConsumer             │
                                                │   @KafkaListener → immutable   │
                                                │   audit record                 │
                                                └────────────────────────────────┘
```

**Request flow for a transfer (the critical path):**

```
POST /api/v1/transfers
   │
   ├─ 1. JwtAuthenticationFilter  → who is calling?          (401 on failure)
   ├─ 2. Controller @Valid        → is the payload sane?     (400 on failure)
   ├─ 3. TransferService          → BEGIN TRANSACTION
   │      ├─ idempotency key already seen? → return prior result, no write
   │      ├─ load fromAccount + toAccount (versions read)
   │      ├─ caller owns fromAccount?                        (403 on failure)
   │      ├─ fromAccount.balance >= amount?                  (422 on failure)
   │      ├─ create Transaction header
   │      ├─ post DEBIT  LedgerEntry  (fromAccount, -amount)
   │      ├─ post CREDIT LedgerEntry  (toAccount,   +amount)
   │      ├─ update both balances  → Hibernate bumps @Version
   │      └─ COMMIT   ── version mismatch here ⇒ rollback ⇒ 409 Conflict
   │
   ├─ 4. AFTER_COMMIT             → publish to transaction-events
   └─ 5. 201 Created + TransferResponse
```

---

## 2. Entity Design

Four tables. `User` and `Account` are conventional; the double-entry model lives in the split between `Transaction` (the header — *what happened*) and `LedgerEntry` (the lines — *how it hit each account*).

### 2.1 `User`

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` (PK) | Generated. Not sequential, so it is not enumerable. |
| `email` | `String` | **Unique**, indexed. Login identity. |
| `passwordHash` | `String` | BCrypt. Never returned by any endpoint, never logged. |
| `fullName` | `String` | |
| `createdAt` | `Instant` | `@CreationTimestamp` |

One user → many accounts (`@OneToMany(mappedBy = "owner")`, lazy).

### 2.2 `Account`

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` (PK) | |
| `owner` | `User` | `@ManyToOne(fetch = LAZY)`, FK `user_id`, indexed. |
| `accountNumber` | `String` | Unique, human-referenceable. |
| `balance` | `BigDecimal(19,2)` | **Never** `double`. Materialized from the ledger. |
| `status` | `enum` | `ACTIVE`, `FROZEN`, `CLOSED` |
| `version` | `Long` | **`@Version` — the optimistic lock. See §3.** |
| `createdAt` | `Instant` | |

```java
@Entity
@Table(name = "accounts")
public class Account {

    @Id @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Version                       // ← Hibernate manages this. No custom locking code.
    private Long version;
}
```

### 2.3 `Transaction` (header)

One row per business event — one deposit, one withdrawal, one transfer.

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` (PK) | |
| `type` | `enum` | `DEPOSIT`, `WITHDRAWAL`, `TRANSFER` |
| `amount` | `BigDecimal(19,2)` | Always **positive**. Direction lives on the entries. |
| `fromAccount` | `Account` | `@ManyToOne`, nullable for `DEPOSIT` (money enters from the system account). |
| `toAccount` | `Account` | `@ManyToOne`, nullable for `WITHDRAWAL`. |
| `status` | `enum` | `COMPLETED`, `FAILED` |
| `idempotencyKey` | `String` | **Unique**, indexed. A retried request returns the original result instead of moving money twice. |
| `description` | `String` | |
| `createdAt` | `Instant` | Indexed — statements query on it. |
| `entries` | `List<LedgerEntry>` | `@OneToMany(cascade = ALL)` — exactly two, always. |

### 2.4 `LedgerEntry` (the double-entry line) — append-only

| Field | Type | Notes |
|---|---|---|
| `id` | `UUID` (PK) | |
| `transaction` | `Transaction` | `@ManyToOne(optional = false)`, FK indexed. |
| `account` | `Account` | `@ManyToOne(optional = false)`, FK indexed. |
| `direction` | `enum` | `DEBIT` (money out) / `CREDIT` (money in) |
| `amount` | `BigDecimal(19,2)` | Positive magnitude. |
| `signedAmount` | `BigDecimal(19,2)` | `-amount` for DEBIT, `+amount` for CREDIT. Makes the "sums to zero" check a single `SUM()` query. |
| `balanceAfter` | `BigDecimal(19,2)` | Snapshot for the statement view. |
| `createdAt` | `Instant` | |

**Never updated. Never deleted.** A correction is a new, reversing transaction.

### 2.5 Why the header/line split

The PRD describes a transaction as "one debit entry + one credit entry." That pair is exactly what `LedgerEntry` models; `Transaction` is the envelope that binds the pair together and guarantees they are created and committed as a unit.

Putting both sides on a single flat `Transaction` row (columns `fromAccount`, `toAccount`, `amount`) looks simpler, but it makes the core invariant unverifiable: you cannot write one query that proves the system's money sums to zero, and deposits/withdrawals — which have only one internal side — become special cases the invariant does not cover. With explicit entries, the invariant is a single query, and it is asserted as a post-condition in every money-movement test:

```sql
-- Invariant 2: the ledger balances. Must return exactly 0.00, always.
SELECT COALESCE(SUM(signed_amount), 0) FROM ledger_entries;

-- Invariant 3: stored balance reconciles with the ledger. Must return zero rows.
SELECT a.id
FROM accounts a
LEFT JOIN ledger_entries le ON le.account_id = a.id
GROUP BY a.id, a.balance
HAVING a.balance <> COALESCE(SUM(le.signed_amount), 0);
```

Deposits and withdrawals are not exempt: they post against a reserved **system account**, so every transaction in the database — internal or external — has two entries and satisfies the invariant.

```
Transfer of 500 from A to B, atomically in one DB transaction:

   Transaction #tx-42  TRANSFER  amount=500  status=COMPLETED
        │
        ├── LedgerEntry  account=A  DEBIT   amount=500   signed = -500
        └── LedgerEntry  account=B  CREDIT  amount=500   signed = +500
                                                       ─────────────
                                                sums to      0  ✓
```

---

## 3. Concurrency Strategy — Optimistic Locking via `@Version`

### The mechanism

`Account.version` is annotated `@Version`. Hibernate then appends the version to every `UPDATE`:

```sql
UPDATE accounts
   SET balance = ?, version = ?          -- version + 1
 WHERE id = ? AND version = ?            -- the version we read
```

If another transaction committed in the meantime, the row's version has already moved, `WHERE` matches zero rows, and Hibernate throws `OptimisticLockException` / `ObjectOptimisticLockingFailureException`. The transaction rolls back — **no partial write, no lost update**.

```
        Thread 1                              Thread 2
   ─────────────────────────────────────────────────────────────
   read A (balance 100, v=7)            read A (balance 100, v=7)
   check 100 >= 30  ✓                   check 100 >= 30  ✓
   compute 70                           compute 70
   UPDATE ... WHERE version=7  ✓        UPDATE ... WHERE version=7
   commit → balance 70, v=8                → 0 rows matched
                                        → OptimisticLockException
                                        → ROLLBACK → 409 Conflict
   ─────────────────────────────────────────────────────────────
   Result: balance = 70, one withdrawal succeeded, one was rejected.
   Without @Version: balance = 70, BOTH succeeded, 30 units vanished.
```

No custom concurrency algorithm is written anywhere in this codebase. This is a framework capability used deliberately.

### Handling the conflict

`TransferService` is annotated `@Transactional`. On conflict:

1. The transaction rolls back — no ledger entries, no balance change, **no Kafka event** (publication is post-commit).
2. The exception is translated by `@RestControllerAdvice` to **`409 Conflict`** with code `CONCURRENT_MODIFICATION`.
3. The client may retry. A bounded server-side retry (`@Retryable`, 3 attempts with backoff) is applied to transfers, since the operation is idempotent under its idempotency key — a retry cannot double-spend.

### Why optimistic, not pessimistic

| | **Optimistic (`@Version`)** — chosen | **Pessimistic (`SELECT … FOR UPDATE`)** |
|---|---|---|
| Cost on the common path | Zero. No lock is taken; contention is detected only at write time. | A row lock is held for the whole transaction, on every request, even uncontended ones. |
| Behaviour under contention | Loser fails fast and retries. | Loser blocks, waiting on the lock. |
| Deadlock risk | **Real, and mitigated the same way.** See the note below — an earlier version of this table claimed "none", which was wrong. | Real: transfer A→B and B→A can deadlock unless accounts are always locked in a fixed order. |
| Throughput at low contention | High. | Lower — serialized per account. |
| Throughput at high contention | Degrades (retry storms). | More stable. |
| Failure mode | Explicit exception you must handle. | Silent waiting, then lock timeout. |

**The decision rests on the expected access pattern.** In a wallet system, contention is per-account, not global — the hot spot would be many concurrent writes to *the same* account, which is rare for a personal wallet. Ordinary traffic is thousands of users touching thousands of distinct accounts, i.e. **low contention**. Paying a lock acquisition on every single request to defend against a rare collision is the wrong trade, and pessimistic locking on a two-account transfer additionally invites deadlock (A→B racing B→A) unless lock ordering is imposed by hand.

Optimistic locking costs nothing when there is no conflict, and when there *is* one it fails loudly and safely — which is precisely the property the correctness guarantee needs.

**Where pessimistic locking is the right call instead:** a shared account that every write touches. This was written as a hypothetical — "out of scope here" — and it was wrong: the system account is exactly that workload, and it had been one since Phase 4.

### The system account, measured

Every deposit and withdrawal posts its counter-entry against the single seeded system account, so two people paying into two unrelated accounts still write the same row. Phase 8 measured it (`TransferLoadIT`), twelve concurrent depositors, each into an account of their own, nobody sharing a user account with anybody:

| | Conflict rate | Accepted |
|---|---|---|
| Deposits, optimistic locking on the system account | **87.1%** | 23 of 178 |
| Deposits, `PESSIMISTIC_WRITE` on the system account | **0.0%** | 157 of 157 |
| Transfers (never touch the system account), before | 36.7% / 33.5% | — |
| Transfers, after | 36.7% / 34.2% | — |

Nearly nine deposits in ten were being rolled back over a row none of those callers cared about. Successful deposits went from about 4 per second to about 26. The transfer figures are the control: they do not touch the system account, and the change did not move them.

So `AccountRepository.findByIdForUpdate` takes `@Lock(PESSIMISTIC_WRITE)`, and `LedgerService` uses it **for the system account only**. Every other account keeps optimistic locking, for all the reasons in the table above — contention between real users is rare, and a lock on every account would serialise the whole API to buy nothing.

**Why this cannot deadlock.** A transfer never touches the system account, so the only transactions taking this lock are deposits and withdrawals, and each takes it exactly once. The system account's id (`00000000-…-0001`) also sorts below every generated account id, so it is the first row updated by the ordered-update rule below. Both orderings agree, and no cycle can form.

**What it costs.** Deposits and withdrawals now serialise on that row rather than racing for it, and the wait is unbounded (PostgreSQL's default `lock_timeout`). That is acceptable while the locking transaction is a handful of statements long. If throughput on that row ever becomes the ceiling, the next step is not a longer wait — it is removing the shared row: shard the system account into several, or drop its materialised balance and derive it from its entries.

### Deadlock: optimistic locking does not exempt you

"No explicit locks" is not the same as "no locks". Every `UPDATE` takes a row-level write lock that is held until the transaction commits, whatever concurrency strategy the application thinks it is using. So the classic cycle is entirely reachable here:

```
   Transfer A→B                          Transfer B→A
   ─────────────────────────────────────────────────────────────
   UPDATE accounts SET ... WHERE id=A    UPDATE accounts SET ... WHERE id=B
   (holds write lock on A)               (holds write lock on B)
   UPDATE ... WHERE id=B  ── waits ──►   UPDATE ... WHERE id=A  ── waits ──►
                    └──────────── cycle ────────────┘
   PostgreSQL's deadlock detector kills one: SQLSTATE 40P01
   → CannotAcquireLockException → not an optimistic failure
```

This was found by the Phase 5 test that fires A→B and B→A simultaneously. It surfaced as a **500**, because the retry and the error handler both recognised only `OptimisticLockingFailureException`, and a deadlock is a *pessimistic* failure.

**The fix is ordering, not retrying.** `LedgerService` applies both balance movements in ascending account-id order, flushing each one so the order the rows are actually locked in is the order written in the code rather than whatever Hibernate chooses at flush time. A global order on the locked rows means no cycle can form.

Retry is kept as a second line: `@Retryable` covers `CannotAcquireLockException` as well, and the handler now maps the whole `ConcurrencyFailureException` family to `409`. Ordering should mean neither ever fires — but a future code path that touches rows in a new order degrades into a retry instead of a 500.

**This risk predates transfers.** A deposit posts `(system, user)` and a withdrawal posts `(user, system)` — opposite orders on the same two rows — so a concurrent deposit and withdrawal on one account could already deadlock in Phase 4. The Phase 4 concurrency tests never mixed the two operations, so nothing exposed it. The ordering fix is applied in the shared posting path and therefore covers deposits and withdrawals too.

### Supporting guarantees

- **Transaction boundary:** the whole transfer runs in one `@Transactional` method. Both entries and both balance updates commit together (Invariant 4).
- **Isolation level:** PostgreSQL default `READ COMMITTED`. `@Version` supplies the missing lost-update protection, so a stricter isolation level is unnecessary.
- **DB-level backstop:** `CHECK (balance >= 0)` on `accounts`. The application must never rely on it — if that constraint ever fires, it is a bug — but it makes the invariant impossible to violate even through a direct SQL write.
- **Money type:** `BigDecimal` with fixed scale 2 throughout. `double` is never used for money.

---

## 4. Kafka Topic Design

### Topic

| Property | Value | Rationale |
|---|---|---|
| Name | `transaction-events` | One topic for all money-movement events. |
| Partitions | 3 | Enough for parallel consumption in a demo. |
| Key | `accountId` (source account; destination account for deposits) | Same key → same partition → **per-account ordering is guaranteed**, which is what an audit log needs. |
| Value | JSON (`TransactionEvent`) | Readable in `kafka-console-consumer` during a demo. |
| Replication factor | 1 (local Docker) | Single broker locally; ≥3 in a real deployment. |
| Retention | 7 days locally | The consumer persists the durable record; the topic is transport. |
| Acks | `all` | Producer waits for full acknowledgement — no silent event loss. |

### Publishing: post-commit, always

The producer is invoked from the service layer **after the database transaction commits**, never inside it:

```java
// Inside the @Transactional service method — registers, does not send:
eventPublisher.publishEvent(new TransactionCompletedEvent(transaction));

// Separate component — fires only if the transaction actually committed:
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onCommitted(TransactionCompletedEvent event) {
    kafkaTemplate.send("transaction-events", event.accountId().toString(), toPayload(event));
}
```

**Why this matters.** If the event were sent inside the transaction and the transaction then rolled back — an optimistic lock conflict, a constraint violation — the audit log would record a transfer that never happened. Kafka has no transaction to roll back with. Publishing after commit makes an event on the topic *proof* of a committed transaction, which is Invariant 5.

The residual failure mode is the reverse: a commit succeeds and the broker is unreachable, so a real transaction goes unpublished. This is the acknowledged trade-off, and it is the safe direction — a missing audit record is recoverable by replaying from the ledger, whereas a fabricated one is not. A transactional outbox table would close the gap; it is noted as a future step, not MVP scope.

### Event payload

```json
{
  "eventId": "8f1c...",
  "transactionId": "3ab7...",
  "type": "TRANSFER",
  "amount": 500.00,
  "fromAccountId": "a1...",
  "toAccountId": "b2...",
  "fromBalanceAfter": 1500.00,
  "toBalanceAfter": 2500.00,
  "status": "COMPLETED",
  "occurredAt": "2026-08-24T10:15:30Z"
}
```

### Consumer

`AuditLogConsumer` — `@KafkaListener(topics = "transaction-events", groupId = "audit-log")` — writes an immutable audit record. Keyed by `eventId` so redelivery is idempotent. Failures route to `transaction-events.DLT` via `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` rather than blocking the partition.

---

## 5. Authentication & Authorization Flow

### Token issue

```
POST /api/v1/auth/login  { email, password }
   │
   ├─ AuthService loads user by email
   ├─ BCryptPasswordEncoder.matches(raw, storedHash)      ← constant-time compare
   ├─ on failure: 401, generic message
   │              (never "wrong password" vs "no such user" — that leaks
   │               which emails are registered)
   └─ on success: JwtService.generateToken(user)
                  HS256, secret from env var, TTL 15 min
                  claims: sub=userId, email, iat, exp
```

### Token validation — every subsequent request

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
   │
   ▼
JwtAuthenticationFilter  (extends OncePerRequestFilter, placed
                          before UsernamePasswordAuthenticationFilter)
   │
   ├─ header missing / malformed        → continue chain → 401 at entry point
   ├─ signature invalid                 → 401  (tampered token)
   ├─ expired                           → 401  (token lifetime enforced)
   └─ valid → load UserDetails
            → set SecurityContextHolder authentication
            → downstream code reads the caller from the SecurityContext,
              NEVER from a userId in the request body
```

### Filter chain configuration

```java
http
  .csrf(csrf -> csrf.disable())                      // stateless API, no cookies
  .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
  .authorizeHttpRequests(auth -> auth
      .requestMatchers("/api/v1/auth/**").permitAll()
      .requestMatchers("/actuator/health").permitAll()
      .anyRequest().authenticated())                 // deny by default
  .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
  .exceptionHandling(e -> e
      .authenticationEntryPoint(jsonAuthEntryPoint)  // 401 as JSON, not an HTML login page
      .accessDeniedHandler(jsonAccessDeniedHandler)) // 403 as JSON
```

`.anyRequest().authenticated()` is the important line: a new endpoint is protected the moment it is written. Exposing one requires an explicit, reviewable decision.

### Authorization (ownership) — the layer people forget

Authentication answers *who is calling*. It does not answer *may they touch this account*. Ownership is checked in the **service layer**, on every account-scoped operation:

```java
private Account loadOwnedAccount(UUID accountId, UUID callerId) {
    Account account = accountRepository.findById(accountId)
            .orElseThrow(() -> new AccountNotFoundException(accountId));
    if (!account.getOwner().getId().equals(callerId)) {
        throw new AccessDeniedException("Account does not belong to caller");
    }
    return account;
}
```

It lives in the service, not the controller, so it cannot be bypassed by a second caller of the same method. Transfers additionally check ownership of the **source** account only — you may credit anyone, but you may only debit yourself.

### Security defaults

- Passwords: BCrypt (strength 12). Never logged, never serialized into any response DTO.
- Secrets: JWT signing key from environment variable. Never committed.
- Validation: `@Valid` on every request DTO; `@Positive` + `@Digits(integer=17, fraction=2)` on all amounts.
- Errors: uniform JSON shape from `@RestControllerAdvice`; no stack traces or SQL in responses.
- SQL injection: parameterized queries throughout via Spring Data JPA. No string-concatenated SQL.
- IDs: UUIDs, so account identifiers cannot be enumerated.

---

## 6. Tech Stack

| Layer | Choice | Version | Why |
|---|---|---|---|
| Language | **Java** | 21 (LTS) | Current LTS; records for DTOs, pattern matching, virtual threads available for load tests. |
| Framework | **Spring Boot** | 3.4.x | Baseline for REST + Security + Data + Kafka. |
| Web | Spring Web MVC | (Boot BOM) | Standard synchronous REST; transaction semantics are simpler to reason about than reactive here. |
| Security | Spring Security | (Boot BOM) | Filter chain, `BCryptPasswordEncoder`. |
| JWT | `jjwt` (`io.jsonwebtoken`) | 0.12.x | HS256 issue/validate. |
| Persistence | Spring Data JPA / **Hibernate** | 6.x | `@Version` optimistic locking — the core of the concurrency strategy. |
| Database | **PostgreSQL** | 16 | Recommended: strict `NUMERIC` semantics for money, mature MVCC, solid `CHECK` constraint support, `READ COMMITTED` default that pairs well with `@Version`. MySQL would work; PostgreSQL is the better fit for a ledger. |
| Migrations | Flyway | 10.x | Versioned schema. `ddl-auto=validate` in every environment — Hibernate never mutates the schema. |
| Messaging | **Apache Kafka** | 3.7 | `transaction-events` topic; Spring Kafka `KafkaTemplate` + `@KafkaListener`. |
| Build | Maven | 3.9+ | |
| Testing | JUnit 5, Mockito, **Testcontainers**, Awaitility | — | Testcontainers runs concurrency tests against real PostgreSQL — an H2 in-memory DB does not reproduce real locking behaviour, so the invariant tests would be meaningless there. |
| API docs | springdoc-openapi | 2.x | Swagger UI at `/swagger-ui.html`. |
| Container | Docker + Docker Compose | — | See below. |

### Docker Compose services

| Service | Image | Port | Notes |
|---|---|---|---|
| `app` | built from project `Dockerfile` | `8080` | `depends_on` db + kafka; waits on health checks. Config via environment variables. |
| `db` | `postgres:16-alpine` | `5432` | Named volume `pgdata` for persistence; healthcheck `pg_isready`. |
| `kafka` | `confluentinc/cp-kafka:7.6.0` | `9092` | `depends_on: zookeeper`; auto-create topics disabled — `transaction-events` is created explicitly with its intended partition count. |
| `zookeeper` | `confluentinc/cp-zookeeper:7.6.0` | `2181` | Kafka coordination. (Kafka 3.7 also supports KRaft mode, which removes this service; Zookeeper is kept here as the more widely documented setup.) |

```bash
docker compose up --build
```

---

## 7. API Contract Summary

Endpoint list only — full request/response schemas live in the OpenAPI spec at `/v3/api-docs` (Swagger UI at `/swagger-ui.html`).

### Auth — public

| Method | Path | Description | Success | Errors |
|---|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Create a user | `201` | `400` validation, `409` email taken, `429` rate limited |
| `POST` | `/api/v1/auth/login` | Exchange credentials for a JWT | `200` | `400`, `401` bad credentials, `429` rate limited |

Both are rate limited per client address — they are the only paths reachable without a
token, so they are the only ones an attacker can hammer without first getting in. A
refusal carries `Retry-After`.

### Accounts — authenticated

| Method | Path | Description | Success | Errors |
|---|---|---|---|---|
| `POST` | `/api/v1/accounts` | Create a wallet for the caller | `201` | `400`, `401` |
| `GET` | `/api/v1/accounts` | List the caller's wallets | `200` | `401` |
| `GET` | `/api/v1/accounts/{id}` | Fetch one wallet | `200` | `401`, `403` not owner, `404` |

### Money movement — authenticated

| Method | Path | Description | Success | Errors |
|---|---|---|---|---|
| `POST` | `/api/v1/accounts/{id}/deposit` | Credit the wallet | `201` | `400`, `401`, `403`, `404`, `409` conflict |
| `POST` | `/api/v1/accounts/{id}/withdraw` | Debit the wallet | `201` | `400`, `401`, `403`, `404`, `409`, `422` insufficient funds |
| `POST` | `/api/v1/transfers` | Transfer between accounts (double-entry, atomic) | `201` | `400`, `401`, `403`, `404`, `409`, `422` |

An idempotency key is scoped to the caller who chose it: the unique index is
`(initiated_by, idempotency_key)`, and the replay lookup is made against the
authenticated user. Two callers may pick the same string without meeting. Repeating a
key with a *different* request — another amount, another pair of accounts — is refused
with `409 IDEMPOTENCY_KEY_REUSED` rather than replayed, because replaying it would
report a movement the caller never asked for as though it had just happened.

### History — authenticated

| Method | Path | Description | Success | Errors |
|---|---|---|---|---|
| `GET` | `/api/v1/accounts/{id}/transactions` | Paginated statement. Query: `page`, `size`, `from`, `to` | `200` | `401`, `403`, `404` |
| `GET` | `/api/v1/transactions/{id}` | Single transaction with both ledger entries | `200` | `401`, `403`, `404` |

### Ops — public

| Method | Path | Description |
|---|---|---|
| `GET` | `/actuator/health` | Liveness/readiness for Docker health checks |

### Error semantics

| Status | Code | Meaning |
|---|---|---|
| `400` | `VALIDATION_ERROR` | Malformed or invalid payload |
| `400` | `SELF_TRANSFER_NOT_ALLOWED` | A transfer named the same account as source and destination |
| `401` | `UNAUTHENTICATED` | Missing, invalid, or expired token |
| `403` | `FORBIDDEN` | Authenticated, but the resource is not the caller's |
| `404` | `NOT_FOUND` | No such account or transaction |
| `405` | `METHOD_NOT_ALLOWED` | The path exists, the method does not |
| `409` | `CONCURRENT_MODIFICATION` | Optimistic lock conflict — safe to retry |
| `409` | `IDEMPOTENCY_KEY_REUSED` | The caller's own key was sent again for a different request; send a new key |
| `422` | `INSUFFICIENT_FUNDS` | Business rule rejected the operation; **no ledger entry was written** |
| `429` | `RATE_LIMIT_EXCEEDED` | Too many requests to a public auth endpoint from this address; carries `Retry-After` |

All errors share one body shape:

```json
{
  "code": "INSUFFICIENT_FUNDS",
  "message": "Account balance is insufficient for this withdrawal",
  "timestamp": "2026-08-24T10:15:30Z",
  "path": "/api/v1/accounts/a1.../withdraw"
}
```

---

## 8. Build Order

Vertical slices, in dependency order. Each is complete — API + logic + persistence + tests + docs — before the next begins.

1. Project skeleton, Docker Compose, Flyway baseline, health check
2. `User` + registration + login + JWT filter chain **+ security tests**
3. `Account` + creation/listing + ownership enforcement **+ authorization tests**
4. `Transaction` + `LedgerEntry` + deposit/withdraw **+ double-entry invariant tests**
5. Transfer with `@Version` optimistic locking **+ the concurrency test suite** ← the centrepiece
6. Statement / transaction history **+ pagination and scoping tests**
7. Kafka producer (post-commit) + audit consumer **+ rollback-emits-no-event test**
