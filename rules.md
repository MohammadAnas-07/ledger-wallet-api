# Project Rules — Wallet & Ledger API

**Companion documents:** [prd.md](prd.md) · [architecture.md](architecture.md) · [phases.md](phases.md)

These are the non-negotiable working rules for this project. They are not style preferences — they are the constraints that make the [Correctness Guarantee](prd.md#5-correctness-guarantee) achievable. When a rule and a deadline conflict, the rule wins.

**Rule 0 — Rules do not get suspended.** "Just this once, without a test" is how a ledger silently stops balancing. If a rule genuinely needs to change, change it in this file first, with the reason written down.

---

## 1. Development Discipline

### 1.1 Feature-by-feature, always

- Every new feature starts on its own branch: **`feature/<name>`** (e.g. `feature/jwt-auth`, `feature/transfer-double-entry`).
- Branch from `main`, one feature per branch. No branch does two things.
- **The whole backend is never built at once.** One vertical slice at a time — API + logic + persistence + tests + docs — as laid out in [phases.md](phases.md).
- A phase is not started before the previous phase is merged and green.

### 1.2 Definition of "complete"

A feature is **not complete** until all three hold:

1. **(a) Unit tests are written and passing.** Not "will add tests later." The tests are part of the slice.
2. **(b) The endpoint is manually verified** via Postman or `curl` — the happy path *and* at least one failure path (unauthorized, invalid input, insufficient funds — whichever apply).
3. **(c) The commit message is clear** — see §1.4.

If any of the three is missing, the feature is in progress, not done. Report it as in progress.

### 1.3 Never merge without explicit confirmation

- **No merge to `main` happens without the user explicitly saying "merge kar do"** (or an equally unambiguous instruction).
- Tests being green is *permission to ask*, not permission to merge.
- This applies to fast-forward merges, squash merges, PR merges, and rebases onto `main` alike.
- Same rule for `git push`, force-push, and tags: only when explicitly asked.

### 1.4 Commits: small, focused, one logical change

- **One commit = one logical change.** Schema migration, service logic, and its tests may share a commit when they form one coherent unit; unrelated refactors never ride along.
- Never mix a refactor with a behaviour change in the same commit — it makes the diff unreviewable and a bad commit impossible to revert cleanly.
- Format: imperative mood, scoped prefix, present tense.

```
feat(transfer): post double-entry ledger rows inside one transaction
fix(auth): reject expired JWT instead of returning 500
test(transfer): add 50-thread concurrent transfer invariant test
chore(docker): pin postgres to 16-alpine
```

- The body explains **why**, when the why is not obvious from the diff. The diff already shows the what.
- Never commit: `.env`, secrets, `target/`, IDE files, commented-out code, debug `System.out.println`.

---

## 2. Security Defaults (Non-Negotiable)

Security is a starting condition, not a hardening pass. Every rule below applies from the first commit of the feature that touches it.

### 2.1 Authentication on everything

- **No endpoint is exposed without authentication**, with exactly these exceptions:
  - `POST /api/v1/auth/register`
  - `POST /api/v1/auth/login`
  - `GET /actuator/health` (Docker health check only — exposes no user data)
- The filter chain ends in `.anyRequest().authenticated()` so a newly written endpoint is protected by default. Making one public requires an explicit line in `SecurityConfig` — a deliberate, reviewable decision.
- **Authentication is not authorization.** Knowing *who* is calling does not establish that they may touch a given account. Every account-scoped operation checks ownership **in the service layer** (not the controller, so it cannot be bypassed by another caller of the same method), and returns `403`.
- The caller's identity always comes from the `SecurityContext` — **never** from a `userId` in the request body or a path variable. A client-supplied user id is an impersonation vector.

### 2.2 Passwords

- Always hashed with **BCrypt** (`BCryptPasswordEncoder`, strength 12). **Never** plaintext, never reversible encoding, never a home-rolled hash.
- `passwordHash` never appears in a response DTO, a log line, an exception message, or a `toString()`.
- Login failure returns one generic `401` message. Never distinguish "wrong password" from "no such user" — that difference tells an attacker which emails are registered.

### 2.3 Input validation on every DTO

- Bean Validation annotations on **every** request DTO field; `@Valid` on **every** controller method parameter that accepts a body.
- Money fields: `@NotNull @Positive @Digits(integer = 17, fraction = 2)` on `BigDecimal`. **Never `double` or `float` for money** — binary floating point cannot represent decimal currency exactly, and rounding drift breaks the ledger invariants.
- Validate at the boundary. A service method should never be the first place a null or a negative amount is noticed.
- Validation failures return `400 VALIDATION_ERROR` in the standard error shape from [architecture.md §7](architecture.md#7-api-contract-summary).

### 2.4 No raw SQL string concatenation

- All data access through Spring Data JPA repositories or parameterized queries (`@Query` with named/positional parameters, `JdbcTemplate` with `?` placeholders).
- **Never** build SQL or JPQL by concatenating user input into a string. Not for search, not for dynamic sorting, not "just for a quick admin query."
- Dynamic filtering (e.g. the statement's date range) uses JPA `Specification` / `Criteria` API, never string building.
- Sort and pagination parameters are validated against an allowlist of column names — `Sort` built from raw user input is an injection path.

### 2.5 Secrets never touch the repository

- DB password, JWT signing secret, Kafka credentials: supplied via **environment variables**, read in `application.yml` as `${JWT_SECRET}` — with **no default fallback value** for anything secret. A fallback default *is* a hardcoded secret, and it is the one that ends up in production.
- `.env` is in `.gitignore` from the first commit. A committed `.env.example` documents the required variable **names** with dummy values only.
- Never hardcode, never commit, never log a secret. Never paste one into a commit message or a doc.
- If a secret is ever committed by accident: rotate it. Deleting the file does not remove it from git history.

---

## 3. Testing Discipline

### 3.1 Unit tests for every service method

- **JUnit 5 + Mockito.** Every public service method has tests covering the happy path and each failure path it can produce.
- Repositories mocked; the service's own logic under test. Fast, no Spring context where a plain unit test suffices.
- A bug fix ships with a test that fails before the fix and passes after. Otherwise there is nothing preventing its return.

### 3.2 Integration tests for critical paths

- **Transfer logic and concurrency are tested against a real PostgreSQL via Testcontainers.** This is not optional for those paths.
- **H2 / in-memory databases are not acceptable for concurrency tests.** They do not reproduce real MVCC and locking behaviour, so a green test there proves nothing about `@Version` — the most important claim in the project would go effectively untested.
- Concurrency tests use `ExecutorService` + `CountDownLatch` to fire genuinely simultaneous requests, and must assert the invariants as post-conditions:
  - `SUM(ledger_entries.signed_amount) == 0` across the system
  - every `account.balance == SUM(its ledger entries)`
  - no balance `< 0`
- Concurrency tests run **repeatedly** (multiple iterations) before a phase is called done. A race condition that appears once in twenty runs is still a race condition — an intermittently failing invariant test is a real bug in the code, never a "flaky test" to be retried away.

### 3.3 Test before shipping

- A feature branch is merge-ready **only when the full suite is green** — not just the new tests.
- Never merge with a `@Disabled`, `@Ignore`, or commented-out test. Deleting a failing test to get green is a correctness failure, not a shortcut.
- Test results are reported honestly. If something fails, say so with the output. Never describe a feature as working when its tests were not run.

---

## 4. Code Hygiene

### 4.1 Delete dead code immediately

- Unused methods, unreferenced classes, commented-out blocks, superseded implementations: **delete them.** Git history is the archive.
- No "maybe use later" code. It is never used later, and it misleads the next reader into thinking it matters.
- Unused imports, unused fields, unreachable branches: gone.

### 4.2 Naming

- Standard Java conventions, consistently: `camelCase` methods and fields, `PascalCase` classes, `UPPER_SNAKE_CASE` constants, lowercase packages.
- Names say what the thing is: `transferFunds`, not `doTransfer2` or `process`. Booleans read as predicates: `hasSufficientFunds`.
- Consistent vocabulary project-wide. `Account` is always an account — never sometimes `Wallet`, sometimes `Acc`. Ledger terms (`DEBIT`, `CREDIT`, `LedgerEntry`) match the domain language in [architecture.md](architecture.md).
- No abbreviations beyond widely understood ones (`id`, `dto`, `jwt`).

### 4.3 No untracked TODOs

- A `TODO` comment is only allowed when a corresponding task is actually tracked — an issue, or a line in [phases.md](phases.md).
- Format includes the reference: `// TODO(phase-8): add rate limiting to /auth/login`
- A bare `// TODO: fix this` is not allowed. Either do it now, or write it down where it will be seen.
- The same goes for `FIXME` and `HACK`. If it is a known compromise, it is a tracked known compromise.

### 4.4 Layer boundaries

- **Entities never cross the API boundary.** Controllers accept and return DTOs (Java records). Serializing a JPA entity leaks internal fields — including `passwordHash` and `version` — and couples the public contract to the schema.
- Business logic lives in the **service layer**. Controllers do HTTP; repositories do persistence. A controller containing an `if` about money is in the wrong place.
- Transaction boundaries (`@Transactional`) belong on service methods — never on controllers, never on repositories.

### 4.5 Errors and logging

- No stack traces, SQL, or internal class names in HTTP responses. `@RestControllerAdvice` translates exceptions to the standard error body.
- Never swallow an exception with an empty `catch`. Handle it or let it propagate.
- Never log passwords, JWTs, or full request bodies containing credentials. Account ids are fine; secrets are not.

---

## 5. Pre-Merge Checklist

Run through this before asking for merge confirmation. Every line must be true.

- [ ] Work is on a `feature/<name>` branch, scoped to one feature
- [ ] Unit tests written; **full suite green**, nothing disabled or deleted to get there
- [ ] Concurrency-affecting change? Integration test against real PostgreSQL, run repeatedly
- [ ] Endpoint manually verified with Postman/curl — happy path **and** a failure path
- [ ] New endpoints authenticated by default; ownership checked in the service layer
- [ ] All request DTOs validated; money is `BigDecimal`, never `double`
- [ ] No secrets, `.env`, or hardcoded credentials in the diff
- [ ] No dead code, no commented-out blocks, no untracked TODOs, no debug printlns
- [ ] Commits small and focused; messages clear
- [ ] Docs updated if the contract or architecture changed
- [ ] **Explicit "merge kar do" received** ← last, and never assumed
