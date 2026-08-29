# Phased Roadmap — Wallet & Ledger API

**Companion documents:** [prd.md](prd.md) · [architecture.md](architecture.md) · [rules.md](rules.md)

Two stages. **Backend is completed fully before frontend work begins.** Within Stage 1, one phase = one feature branch = one vertical slice (API + logic + persistence + tests + docs), merged before the next begins.

Every phase inherits the [rules.md](rules.md) definition of complete: **(a)** unit tests written and passing, **(b)** endpoint manually verified via Postman/curl, **(c)** clear commit messages — plus explicit confirmation before any merge.

---

## Stage 1: Backend — Current Focus

| Phase | Feature | Branch | Status |
|---|---|---|---|
| 1 | Project setup + Docker Compose + health check | `feature/project-setup` | ✅ Merged |
| 2 | User registration + JWT login | `feature/user-auth` | ✅ Merged |
| 3 | Account creation + balance view | `feature/account-management` | ✅ Merged |
| 4 | Deposit / Withdraw + `@Version` optimistic locking | `feature/deposit-withdraw` | ✅ Merged |
| 5 | Transfer between accounts (double-entry, concurrency) | `feature/transfers` | ✅ Merged |
| 6 | Transaction history / statement | `feature/transaction-history` | ✅ Merged |
| 7 | Kafka event publishing + consumer | `feature/kafka-events` | ✅ Merged |
| 8 | Backend hardening (rate limiting, security review, load test) | `feature/hardening` | ◐ In review — suite green, awaiting merge |

Branch names are the ones actually used, which differ from the names planned here for
phases 2, 3 and 5.

---

### Phase 1 — Project Setup

**Branch:** `feature/project-setup`

Foundation only. No business logic in this phase.

- Spring Boot 3.4.x project (Maven), Java 21 — dependencies: Web, Security, Data JPA, Validation, Kafka, PostgreSQL driver, Flyway, Lombok
- Package structure by feature, not by layer-only: `auth`, `account`, `ledger`, `common`
- `git init`, `.gitignore` (target/, `.env`, IDE files) committed **first** — before any code
- `docker-compose.yml`: `app` + `db` (postgres:16-alpine) + `kafka` + `zookeeper`, with health checks and a named volume for Postgres
- `.env.example` with required variable **names** and dummy values; real `.env` gitignored
- `application.yml` reading all secrets as `${VAR}` with **no default fallbacks**
- Flyway baseline migration (`V1__baseline.sql`), `spring.jpa.hibernate.ddl-auto=validate`
- `GET /health` reachable and wired to the Docker health check

**Done when:** `docker compose up --build` starts all four services and `/health` returns `UP`.
**Tests:** context-loads smoke test + health endpoint test.

> **Note — compose verification pending.** Docker Compose end-to-end build is machine par slow Maven dependency downloads aur BuildKit instability ki wajah se locally fully verify nahi ho paya. Unit + integration tests (7/7) pass hain — core logic verified hai. Compose verification ek alag machine ya CI environment mein baad mein confirm hoga.
>
> **Test status: 7/7 green, re-confirmed on a fresh Docker engine** (3 unit + 4 integration, the latter against real PostgreSQL via Testcontainers). An intermediate run had the integration tests fail at Postgres container startup, but that was Docker on this machine degrading under repeated image-build attempts, not application code — a restart of the engine and the same commit returned 7/7.
>
> Worth carrying into Phase 4: on this machine the Postgres container took **1m 46s** to report ready. Testcontainers' 60s default would have failed that run, and `IntegrationTestBase` raises the startup timeout to 3 minutes for exactly that reason. The concurrency suites in Phases 4–5 will do far more container work, so this budget needs revisiting there rather than being assumed.
>
> Manual verification (rules.md definition of complete, item b) was done by running the app directly via `mvn spring-boot:run` against the `db` container: `GET /health` returned `200 {"status":"UP"}` and a protected path returned `401`. What remains unverified is only the full `docker compose up --build` stack, since the application image itself never finished building here.
>
> Dependency note: Lombok is listed above but was never added. Entities are written by
> hand precisely so that no generated `toString()` can put a password hash into a log
> line, and DTOs are records, which is what Lombok would have been for.
>
> Compose note (Phase 8): **verified.** The image was rebuilt from the committed
> source and `docker compose up -d --build app` brought all four services up, with
> `db`, `kafka` and `zookeeper` healthy and the app started in 20s. Through the
> container: register, login, create account, deposit, an idempotent replay that moved
> no money a second time, the statement, a malformed id answered `400`, an
> unauthenticated call answered `401`, and a burst of twenty logins answered ten `401`
> and ten `429` with `Retry-After`. Flyway showed `V6` applied; the database reported
> the ledger summing to `0.00`, no account disagreeing with its entries and none below
> zero; and the deposit's event completed the round trip to the audit log through the
> real broker.
>
> One caveat, so the claim is exact: this was built from the working directory rather
> than a fresh `git clone`. The Dockerfile copies only `pom.xml` and `src`, so the two
> are equivalent in practice, but a literal clean-clone run has not been done.
>
> Endpoint note: `/health` is a plain controller, not Spring Actuator. Actuator was left out of Phase 1 to keep the dependency set to what the phase actually needs; it is a candidate for Phase 8 if a richer readiness probe (DB connectivity, Kafka reachability) becomes useful.

---

### Phase 2 — User Registration + JWT Login

**Branch:** `feature/jwt-auth`
**PRD:** [§3.1](prd.md#31-user-registration--jwt-login) · **Architecture:** [§5](architecture.md#5-authentication--authorization-flow)

- `User` entity + Flyway migration; unique index on `email`
- `POST /api/v1/auth/register` — BCrypt (strength 12), `409` on duplicate email
- `POST /api/v1/auth/login` — issue HS256 JWT, 15 min TTL, secret from env
- `JwtAuthenticationFilter` (`OncePerRequestFilter`), placed before `UsernamePasswordAuthenticationFilter`
- `SecurityConfig`: stateless, CSRF disabled, `.anyRequest().authenticated()`, only `/auth/**` and `/actuator/health` public
- `@RestControllerAdvice` with the standard error body; JSON `401`/`403` handlers (never an HTML login page)

**Done when:** a protected endpoint returns `401` without a token and `200` with a valid one.
**Tests:** password is hashed and never returned; duplicate email → `409`; bad credentials → generic `401` (identical message for unknown email and wrong password); expired token → `401`; tampered signature → `401`.

---

### Phase 3 — Account Creation + Balance View

**Branch:** `feature/accounts`
**PRD:** [§3.2](prd.md#32-account-creation-multiple-wallets-per-user)

Single account operations only — **no money movement yet.**

- `Account` entity + migration: `balance NUMERIC(19,2) NOT NULL DEFAULT 0`, `CHECK (balance >= 0)`, `version BIGINT`, FK to `users`
- `@Version` field is **declared here** but its behaviour is only exercised in Phase 4
- `POST /api/v1/accounts` — create a wallet for the caller (one user → many accounts)
- `GET /api/v1/accounts` — list the caller's own wallets only
- `GET /api/v1/accounts/{id}` — `403` if not the caller's
- Ownership check helper in the **service layer**; caller identity from `SecurityContext`, never from the request body

**Done when:** a user can create multiple wallets and cannot see anyone else's.
**Tests:** ownership enforcement (`403` on another user's account); listing is scoped to the caller; new account starts at `0.00`; entity never serialized directly (DTOs only).

---

### Phase 4 — Deposit / Withdraw

**Branch:** `feature/deposit-withdraw`
**PRD:** [§3.3](prd.md#33-deposit--withdraw) · **Architecture:** [§2](architecture.md#2-entity-design), [§3](architecture.md#3-concurrency-strategy--optimistic-locking-via-version)

**This is where optimistic locking and double-entry first go to work.**

- `Transaction` + `LedgerEntry` entities and migrations
- Reserved **system account** seeded via migration — deposits and withdrawals post against it, so even external money movement produces two entries and satisfies the ledger invariant
- `POST /api/v1/accounts/{id}/deposit` and `.../withdraw`
- Every operation writes a `Transaction` header + **two** `LedgerEntry` rows inside one `@Transactional` boundary
- Insufficient funds → `422 INSUFFICIENT_FUNDS`, **no ledger row written**
- `OptimisticLockingFailureException` → `409 CONCURRENT_MODIFICATION` via the advice
- Idempotency key accepted and enforced (unique index) — a retried request must not move money twice

**Done when:** balances change correctly, and every operation leaves the ledger summing to zero.
**Tests:** unit tests for amount validation and insufficient funds; **integration test (Testcontainers, real PostgreSQL): N concurrent withdrawals where total demand exceeds the balance — only the affordable subset succeeds, balance never goes negative, ledger sums to zero.** Retried idempotency key does not double-apply.

---

### Phase 5 — Transfer Between Accounts

**Branch:** `feature/transfer-double-entry`
**PRD:** [§3.4](prd.md#34-transfer-between-accounts-double-entry-atomic) · **Correctness:** [§5](prd.md#5-correctness-guarantee)

**The centrepiece of the project.** Everything before this exists to make this phase provable.

- `POST /api/v1/transfers` — `{ fromAccountId, toAccountId, amount, idempotencyKey }`
- One `@Transactional` boundary: debit entry + credit entry + both balance updates commit together or not at all
- Both accounts optimistically locked via `@Version`; conflict → rollback → `409`
- Ownership checked on the **source** account only — you may credit anyone, but only debit yourself
- Rejected before any write: self-transfer, non-positive amount, unknown account, unauthorized source
- Bounded retry (`@Retryable`, 3 attempts with backoff) — safe because the idempotency key prevents double-spend

**Done when:** the concurrency suite passes **repeatedly** with zero imbalance. Passing 19 times out of 20 means this phase is not done.

**Tests — the flagship suite** (Testcontainers, real PostgreSQL; `ExecutorService` + `CountDownLatch`):
- N threads transferring across a pool of accounts → total system balance unchanged; every account reconciles with its ledger; nothing negative
- Concurrent transfers into and out of the same account → no lost update
- Version conflict rolls back cleanly with no partial write
- All three invariants asserted as post-conditions on every money-movement test
- Multiple iterations per run — an intermittent failure here is a real bug, never a flaky test

---

### Phase 6 — Transaction History / Statement

**Branch:** `feature/transaction-history`
**PRD:** [§3.5](prd.md#35-transaction-history--statement)

- `GET /api/v1/accounts/{id}/transactions?page=&size=&from=&to=` — paginated, newest first
- `GET /api/v1/transactions/{id}` — single transaction with both ledger entries
- Read-only. Ledger entries are append-only — never updated, never deleted
- Date filtering via JPA `Specification`, **not** string-built queries; sort fields validated against an allowlist
- Index on `(account_id, created_at)` for the statement query

**Done when:** a user can read their own statement and no one else's.
**Tests:** scoping (`403` on another user's statement); pagination and ordering; date-range filtering; sum of returned entries reconciles with the stored balance.

---

### Phase 7 — Kafka Event Publishing + Consumer

**Branch:** `feature/kafka-events`
**PRD:** [§3.6](prd.md#36-kafka-event-publish-on-every-transaction) · **Architecture:** [§4](architecture.md#4-kafka-topic-design)

- Topic `transaction-events` created explicitly (3 partitions, key = accountId, auto-create disabled)
- Producer with `acks=all`, publishing **post-commit** via `@TransactionalEventListener(phase = AFTER_COMMIT)` — never inside the transaction
- `AuditLogConsumer` (`@KafkaListener`, group `audit-log`) writing an immutable audit record, idempotent on `eventId`
- Failures route to `transaction-events.DLT` rather than blocking the partition

**Done when:** a committed transfer produces exactly one event and the consumer logs/persists it; a rolled-back transfer produces **zero**.
**Tests:** rollback emits no event (Invariant 5); committed transaction emits exactly one with a correct payload; redelivery of the same `eventId` does not duplicate the audit record.

---

### Phase 8 — Final Backend Hardening

**Branch:** `feature/hardening`

No new features. Proving what exists is sound.

- **Rate limiting on auth endpoints** — `/auth/login` and `/auth/register` (Bucket4j or a filter); brute-force protection, `429 Too Many Requests`
- **Full security review against the [rules.md §5 checklist](rules.md#5-pre-merge-checklist)** — every endpoint audited: authenticated by default, ownership enforced in the service layer, all DTOs validated, no entity serialized to the wire, no secret in the repo or in logs
- **Load test the transfer endpoint** for race conditions — sustained concurrent traffic, then assert the invariants against the database afterwards; confirm the ledger still sums to zero
- **Revisit locking on the system account.** Every deposit and withdrawal posts its counter-entry against the single seeded system account, so that one row is a contention hotspot: two users depositing into *different* accounts can still lose an optimistic lock race against each other. Correctness is unaffected — the loser rolls back cleanly and returns 409 — but under sustained load the retry rate is driven by an account nobody is actually competing for. Options to weigh under load-test numbers: `@Lock(PESSIMISTIC_WRITE)` on the system account only (with a fixed lock ordering to avoid deadlock against transfers), splitting it into several shard accounts summed on read, or dropping its materialised balance and deriving it from its entries. This is the workload architecture.md §3 names as the case where optimistic locking is the wrong default
- Verify `docker compose up` works end to end from a clean clone
- README with setup instructions and a demo walkthrough
- Confirm [architecture.md](architecture.md) matches what was actually built; correct it where it drifted

**Done when:** all six MVP features work end to end, the concurrency suite passes repeatedly, and every [PRD success criterion](prd.md#7-success-criteria) is checked off.

#### What Phase 8 actually did

**Rate limiting** — Bucket4j, per client address, ahead of the JWT filter so a throttled
request costs no database read and no BCrypt comparison. Login 10/min, register 5/min,
`429` with `Retry-After`.

**Security review** — the [Find Security Gaps](memory.md#workflow-find-security-gaps)
workflow was run over the whole codebase. Clean on authentication coverage, ownership,
passwords, input validation, SQL injection, secrets and JWT expiry. Findings fixed:

| # | Severity | Finding | Fix |
|---|---|---|---|
| 1 | HIGH | Idempotency keys were global, so guessing one replayed a stranger's transaction back to you — and taking one first turned their real request into a no-op | Keys scoped to their initiator (`V6`) |
| 4 | MEDIUM | A key reused for a *different* request replayed the old result instead of refusing | `409 IDEMPOTENCY_KEY_REUSED` |
| 5 | MEDIUM | Malformed input, wrong methods and unknown paths returned `500` and logged a stack trace each time | Proper `400` / `404` / `405` |
| 7 | LOW | Statement page number was unbounded, so a huge OFFSET was cheap to ask for | Clamped, like the page size |

Deliberately **not** fixed, and now recorded as known limitations in
[README.md](README.md): registration still reveals whether an address is registered
(`409`), tokens cannot be revoked before they expire, rate limiting is per instance,
and a committed transaction can go unpublished if the broker is unreachable. `FROZEN`
and `CLOSED` account statuses are still declared but unenforced — nothing sets them
today, and the check belongs with the feature that first does.

**Load test** — `TransferLoadIT`: sustained multi-threaded traffic, invariants asserted
against the database after every round. It also answered the system account question
this file raised in Phase 4.

**System account** — measured before deciding, as planned. With twelve concurrent
depositors, each into an account of their own, **87% of deposits were failing** on the
one shared row; `PESSIMISTIC_WRITE` on that account alone took it to **0%**, and
transfers — which never touch it — did not move (36.7% before and after). Locked, and
written up in [architecture.md §3](architecture.md#3-concurrency-strategy--optimistic-locking-via-version).

**Docs** — README rewritten from the "coming soon" placeholder. architecture.md
corrected where it had drifted: it documented a Swagger UI and an `/actuator/health`
endpoint that were never built, a `/api/v1/auth/**` wildcard the filter chain
deliberately does not use, and two error codes the application never emits.

---

## Stage 2: Frontend — Later, Separate Effort

> **Frontend kaam Stage 1 complete hone se pehle shuru nahi hoga — koi bhi UI-related task is beech mein aaye to usse Stage 2 mein park karo, abhi mat karo.**

This applies without exception, including to small or "quick" UI requests: a login screen, a single page, a bit of styling, "just to see how it looks." Anything UI-related that surfaces during Stage 1 gets written down in the parking lot below and left there. Backend correctness is the entire point of this project; a half-built UI competing for attention is exactly what would compromise it.

**Trigger:** Stage 2 begins **only** when every Stage 1 phase (1–8) is complete, merged, and tested — not when the backend "mostly works."

**When triggered:**
- `design.md` (Apple-minimalist reference) is loaded **at that point**, not before. It is deliberately not part of the current context.
- The detailed phase breakdown for Stage 2 gets written then, once the real API contract is settled — planning UI phases against endpoints that may still shift would just be rework.

**Planned scope (outline only, not a commitment):**
- Login screen
- Account balance / wallet list view
- Transfer form
- Transaction history table

**Not in Stage 2 either:** anything on the [PRD out-of-scope list](prd.md#4-out-of-scope) — multi-currency, payment gateways, admin panel, notifications.

---

### Parking Lot

UI ideas that come up during Stage 1 go here. Written down, not acted on.

| Idea | Noted on |
|---|---|
| _(empty)_ | |
