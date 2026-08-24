# Product Requirements Document — Wallet & Ledger API

**Project:** `ledger-wallet-api`
**Status:** Draft v1.0
**Domain:** Financial correctness under concurrency

---

## 1. Problem Statement

Money-moving systems break in a way ordinary CRUD applications do not: they break *silently*.

A typical wallet implementation reads an account balance, computes a new value in application memory, and writes it back:

```
read balance (100) -> subtract 30 -> write balance (70)
```

Under concurrent load this is a classic **lost update / read-modify-write race condition**. Two simultaneous withdrawals of 30 each against a balance of 100 can both read `100`, both compute `70`, and both write `70` — 30 units disappear from the ledger and the system has no record that anything went wrong. The inverse also happens: a balance can be driven **negative** because two requests independently pass the "sufficient funds" check before either has committed.

A second, related failure mode is **partial writes**. A transfer is logically two operations — debit the sender, credit the receiver. If these are not atomic and durable as a single unit, a crash or exception between them creates money out of nothing, or destroys it. There is no cheap way to detect this after the fact unless the data model itself makes the imbalance visible.

This project solves both problems explicitly:

1. **Race conditions** are prevented using **JPA `@Version` optimistic locking** on the `Account` entity. Concurrent writers to the same account are detected by the persistence provider, and the losing transaction fails loudly with a conflict instead of silently corrupting state. No custom locking algorithm is written — the framework's built-in mechanism is used deliberately.
2. **Partial writes and invisible imbalance** are prevented using **double-entry bookkeeping**. Every transaction produces exactly one debit entry and one matching credit entry, written inside a single database transaction. The sum of all ledger entries in the system is therefore always zero, which makes correctness *auditable* rather than merely assumed.

The deliverable is a REST API that can be hammered with concurrent transfers and still produce a ledger that balances exactly.

---

## 2. Target Users

This is a portfolio/demo system, framed as the backend of a **hypothetical fintech product** — a digital wallet startup. Users are described from that perspective.

| User | Description | What they need from the API |
|---|---|---|
| **End user (wallet holder)** | A customer of the hypothetical fintech app. Holds one or more wallets. | Register, log in, create wallets, deposit, withdraw, transfer to another account, view statement. |
| **Internal audit / compliance consumer** | A downstream service that must retain an immutable record of every money movement. | A reliable event stream of every committed transaction (Kafka `transaction-events`). |
| **Backend engineer (integrator)** | Engineer building the mobile/web client against this API. | Predictable REST contracts, meaningful HTTP status codes, clear error semantics on conflict and insufficient funds. |
| **Reviewer / hiring manager** *(the real audience)* | Evaluating engineering judgement, not product-market fit. | Readable code, vertical slices, and tests that actually prove the concurrency claims. |

---

## 3. Core Features (MVP Scope, Prioritized)

Features are built as **vertical slices**: one feature = API endpoint + service logic + persistence + tests + docs. A feature is not "done" until its tests exist and pass.

### P0 — Foundation

#### 3.1 User Registration + JWT Login

- `POST /api/v1/auth/register` — create a user with email + password.
- `POST /api/v1/auth/login` — validate credentials, issue a signed JWT.
- Passwords stored with **BCrypt**; never in plaintext, never logged.
- All subsequent endpoints require `Authorization: Bearer <token>`.
- Input validation on every field (email format, password strength) via Bean Validation.

**Acceptance:** unauthenticated calls to protected routes return `401`. Duplicate email returns `409`. Expired tokens are rejected.

#### 3.2 Account Creation (Multiple Wallets per User)

- `POST /api/v1/accounts` — create a wallet for the authenticated user.
- `GET /api/v1/accounts` — list the caller's own wallets.
- `GET /api/v1/accounts/{id}` — fetch a single wallet.
- One user may own **many** accounts (one-to-many). A user may only read or act on accounts they own — ownership is enforced in the service layer, not just the controller.

**Acceptance:** accessing another user's account returns `403` and leaks no data.

### P1 — Money Movement

#### 3.3 Deposit / Withdraw

- `POST /api/v1/accounts/{id}/deposit`
- `POST /api/v1/accounts/{id}/withdraw`
- Amounts are `BigDecimal`, strictly positive, fixed scale (2 decimal places). Never `double`/`float`.
- Withdraw rejects insufficient funds with `422 Unprocessable Entity` and a typed error code, writing **no** ledger entry.
- Both operations are recorded as ledger entries against a system counterparty account, so double-entry invariants hold even for money entering and leaving the system.

**Acceptance:** balance never goes below zero, including under concurrent withdrawal attempts.

#### 3.4 Transfer Between Accounts (Double-Entry, Atomic)

- `POST /api/v1/transfers` — `{ fromAccountId, toAccountId, amount, idempotencyKey }`.
- Executed inside a single `@Transactional` boundary: debit entry + credit entry + both balance updates commit together or not at all.
- Both participating accounts are optimistically locked via `@Version`; a version conflict aborts the transaction and surfaces as `409 Conflict` (the client may retry).
- Self-transfer, zero/negative amounts, unknown accounts, and unauthorized source accounts are all rejected before any write.
- The **idempotency key** prevents a retried request from moving money twice.

**Acceptance:** N concurrent transfers against the same account produce a ledger whose entries sum to zero and balances equal to the arithmetic expectation. This is the flagship test of the project.

### P2 — Visibility

#### 3.5 Transaction History / Statement

- `GET /api/v1/accounts/{id}/transactions?page=&size=&from=&to=` — paginated, newest first, filterable by date range.
- Returns ledger entries with direction (`DEBIT` / `CREDIT`), amount, counterparty, timestamp, and transaction reference.
- Read-only. Ledger entries are **append-only** — never updated, never deleted. Corrections are made by posting a reversing entry.

**Acceptance:** the statement is scoped to the caller's own account, and the sum of its entries reconciles with the stored balance.

#### 3.6 Kafka Event Publish on Every Transaction

- Every committed transaction publishes an event to the `transaction-events` topic.
- Published **post-commit** via `@TransactionalEventListener(phase = AFTER_COMMIT)`, so no event is ever emitted for a rolled-back transaction.
- Payload: transaction id, type, source/destination account, amount, timestamp, resulting balances.
- Consumed by an audit-log consumer that writes an immutable record — event-driven audit, decoupled from the write path.

**Acceptance:** a rolled-back transfer produces zero events; a committed transfer produces exactly one.

---

## 4. Out of Scope

Explicitly excluded to prevent scope creep. These are deliberate omissions, not oversights.

- **Multi-currency support** — all amounts are in a single implied currency. No FX rates, no conversion, no per-currency sub-ledgers.
- **Real payment gateway integration** — no Razorpay / Stripe / UPI. Deposits and withdrawals are simulated against a system counterparty account.
- **Admin panel / back-office UI** — no admin roles, no dashboards, no manual balance adjustment tooling. API only.
- **Notifications** — no email, SMS, or push. The Kafka stream exists for audit, not for user-facing alerts.
- **KYC, fraud scoring, transaction limits, interest accrual, PDF statements** — out of scope entirely.
- **Distributed-scale concerns** (distributed locks, sharding, read replicas) — single-instance correctness is the goal; the concurrency story lives at the database level.

---

## 5. Correctness Guarantee

> **This section is non-negotiable. Every other feature is negotiable; this one is not.**

The system must satisfy the following invariants at all times, including under sustained concurrent load.

### Invariant 1 — No Negative Balances

No account balance may ever be negative. A withdrawal or transfer that would breach this is rejected before any write. This holds even when many concurrent requests each individually pass the sufficient-funds check, because the balance write is guarded by an optimistic lock version check that fails the losing writer.

### Invariant 2 — The Ledger Always Balances

For any transaction, `sum(debit entries) == sum(credit entries)`. Across the whole system, the sum of all signed ledger entries is exactly **zero**. The application never creates or destroys money — it only moves it.

### Invariant 3 — Balance Reconciles With Ledger

For every account: `account.balance == sum(all ledger entries for that account)`. The stored balance is a materialized convenience; the ledger is the source of truth, and the two must never diverge.

### Invariant 4 — Atomicity

A transfer is all-or-nothing. There is no observable state in which the sender has been debited but the receiver has not been credited. Guaranteed by a single database transaction boundary around both entries and both balance updates.

### Invariant 5 — No Phantom Events

An event on `transaction-events` implies a committed transaction. A rolled-back transaction never emits an event. Guaranteed by post-commit publication.

### How These Are Proven

These are not claims in a README — they are enforced by tests that ship with each feature.

| Test | What it proves |
|---|---|
| **Concurrent withdrawal test** — N threads withdraw from one account, total demand > balance | Invariant 1: only the affordable subset succeeds; balance lands at ≥ 0, never negative |
| **Concurrent transfer test** — N threads transfer across a pool of accounts via `ExecutorService` + `CountDownLatch` | Invariants 2 and 3: total system balance unchanged; every account reconciles with its ledger |
| **Optimistic lock conflict test** | A version conflict raises `OptimisticLockingFailureException`, rolls back cleanly, and surfaces as `409` |
| **Rollback event test** | A failing transfer publishes zero Kafka events |
| **Ledger sum assertion** (post-condition on every money-movement test) | Invariant 2, continuously |

**Definition of done for the project:** run the concurrency suite repeatedly; the ledger balances every single time. If it balances 99 times out of 100, the project is not done.

---

## 6. Engineering Principles

Three rules governing how this project is built.

1. **Test before shipping.** No feature counts as complete without unit tests; money-movement features additionally require concurrency tests. Tests are written as part of the slice, not retrofitted.
2. **Build feature by feature.** Development proceeds in small vertical slices — API + logic + persistence + tests + docs — one at a time. No slice starts before the previous one is green.
3. **Secure everything by default.** Authentication, input validation, and ownership checks are applied at every layer from the first commit. Security is a starting condition, not a hardening pass at the end.

---

## 7. Success Criteria

The project is successful when:

- [ ] All six MVP features are implemented as tested vertical slices.
- [ ] The concurrency suite passes repeatedly, with zero ledger imbalance and zero negative balances.
- [ ] Every protected endpoint rejects unauthenticated and unauthorized access.
- [ ] Every money-movement request produces exactly one Kafka event on commit, and none on rollback.
- [ ] `docker compose up` brings up app + database + Kafka + Zookeeper, and the API is usable end to end.
- [ ] `architecture.md` accurately describes what was actually built.
