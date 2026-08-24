# Workflows — Wallet & Ledger API

**Companion documents:** [prd.md](prd.md) · [architecture.md](architecture.md) · [rules.md](rules.md) · [phases.md](phases.md) · [design.md](design.md)

Reusable workflow prompts, invoked by name during development. Unlike the other docs in this repo — which describe the project once — these are **repeatable commands**.

**How to invoke:** name the workflow, optionally with a scope or input.

> "Run **Find Security Gaps** on the transfer module"
> "**Debug an Error Fast**: `<paste stack trace>`"
> "Run **E2E Test My App**"

Each block below is self-contained: it can be copied and pasted on its own, or referenced by name from this file.

**Two rules apply across every workflow in this file:**

1. **Report before changing.** Every workflow that could modify code stops and reports first. Explicit go-ahead is required before edits.
2. **Stay in scope.** No "while I'm here" refactors, no unrelated cleanups, no opportunistic improvements. The workflow does what it says and nothing else.

---

## Workflow: Find Security Gaps

**Invoke as:** "Run Find Security Gaps" — optionally scoped: "…on the auth module" / "…on `TransferService`"

**When invoked:** review the current codebase (or the specified module) against the [rules.md security checklist](rules.md#2-security-defaults-non-negotiable). Audit every item below; do not stop at the first finding.

**Checklist:**

1. **Authentication coverage** — every endpoint except `POST /api/v1/auth/register` and `POST /api/v1/auth/login` requires authentication. Read `SecurityConfig` and confirm the chain ends in `.anyRequest().authenticated()`. Then cross-check **every** `@RequestMapping`/`@GetMapping`/`@PostMapping` in the codebase against the permitted list — look specifically for routes made public accidentally via a broad `permitAll()` pattern (e.g. `/api/**`, a stray `/**`, or a `permitAll()` on a parent path). Also flag `@PermitAll`, `@CrossOrigin(origins = "*")`, and any disabled security filter.
2. **Authorization / ownership** — separate from authentication. Every account-scoped operation must verify the caller owns the account, **in the service layer**, returning `403`. Flag any handler that reads a `userId`/`accountId` from the request body or path and acts on it without an ownership check — that is an impersonation vector.
3. **Passwords** — hashed with BCrypt. Never plaintext, never in a response DTO, never in a log statement, never in `toString()`/`@Data`-generated output. Confirm login failure returns one generic message for both unknown email and wrong password.
4. **Input validation** — every request DTO field carries Bean Validation annotations, and every controller body parameter carries `@Valid`. Money fields must be `BigDecimal` with `@Positive` and `@Digits(integer = 17, fraction = 2)`. **Flag any `double` or `float` used for money as high severity.**
5. **SQL injection** — no raw or string-concatenated SQL/JPQL anywhere. All access via Spring Data repositories or parameterized queries. Flag string concatenation into `@Query`, `createQuery`, `createNativeQuery`, or `JdbcTemplate`. Also flag `Sort`/sort-column values built from unvalidated user input.
6. **Hardcoded secrets** — grep for suspicious literal assignments and inspect each hit:

```bash
grep -rnEi "(password|secret|api[_-]?key|token|credential)[\"']?\s*[:=]\s*[\"'][^\"']{6,}" --include=*.java --include=*.yml --include=*.yaml --include=*.properties --include=*.xml .
```

   Also flag: any `${VAR:default}` fallback on a secret (a default *is* a hardcoded secret), a committed `.env`, `.env` missing from `.gitignore`, and secrets appearing in `docker-compose.yml` as literals.
7. **JWT expiry** — token TTL is enforced and finite. Flag any token issued without an `exp` claim, an unreasonably long TTL, or a validation path that does not check expiry. Confirm the signing secret comes from the environment and is of adequate length for HS256.
8. **Rate limiting** — present on `/auth/login` and `/auth/register`. **If missing, report it as a gap** (expected until Phase 8 — note it as such rather than treating it as a surprise).

**Output format** — a numbered list, highest severity first:

```
1. [HIGH] No ownership check on statement endpoint
   File: src/main/java/.../TransactionController.java:47
   Any authenticated user can read any account's history by changing the path id.
   Rule: rules.md §2.1 — ownership enforced in the service layer.
   Suggested fix: route through AccountService.loadOwnedAccount(id, callerId).
```

Severity guide: **HIGH** = exploitable now (auth bypass, data leak, injection, exposed secret). **MEDIUM** = weakens a defence but not directly exploitable (missing validation, weak TTL). **LOW** = hygiene (an over-broad CORS entry in a dev profile).

If the codebase is clean on an item, say so explicitly — a checklist with silent gaps is worse than no checklist.

**Constraint:** **Do not fix anything automatically.** Report first, then wait for explicit go-ahead before changing any code.

---

## Workflow: Debug an Error Fast

**Invoke as:** "Debug an Error Fast: `<error message / stack trace>`"

**When invoked**, follow this order. Do not skip ahead to a fix.

1. **Read the actual stack trace, top to bottom.** Identify the exact failing line and the immediate cause. **Do not guess broadly** or theorise about likely causes before reading the trace. Find the deepest frame in project code (not framework internals) — that is usually where the bug is, even when the exception is thrown further down. Read `Caused by:` chains all the way to the root; the last one is usually the real cause.
2. **Classify the failure** before proposing anything:
   - **Data problem** — null value, missing entity, empty `Optional`, absent FK, unseeded reference row
   - **Logic problem** — wrong method call, inverted condition, wrong order of operations, incorrect transaction boundary
   - **Config problem** — missing bean, wrong `application.yml` value, unset env var, absent migration, container not up
   
   State the classification explicitly. It determines where the fix belongs, and misclassifying is what turns a two-line fix into an afternoon.
3. **Propose the minimal fix.** The smallest change that addresses the identified cause. **Not a refactor. No "while I'm here" changes.** No renaming, no reformatting, no tidying nearby code. If a larger change seems warranted, say so as a separate recommendation — do not bundle it into the fix.
4. **State what test would have caught this**, and offer to add it. Per [rules.md §3.1](rules.md#31-unit-tests-for-every-service-method), a bug fix ships with a test that fails before the fix and passes after.

**Project-specific quick checks** — in this codebase, check these before going deeper:

- `OptimisticLockingFailureException` → this is often **correct behaviour**, not a bug. Verify it is being translated to `409`, not leaking as a `500`.
- `LazyInitializationException` → a lazy association touched outside the transaction, usually via entity serialization. Confirm a DTO is being returned, not an entity.
- Balance/ledger mismatch → check the `@Transactional` boundary covers both entries and both balance updates. See [architecture.md §3](architecture.md#3-concurrency-strategy--optimistic-locking-via-version).
- Missing Kafka event → confirm publication is `AFTER_COMMIT` and that the transaction actually committed.
- `401` where a `200` was expected → token expiry before signature; check clock and TTL.
- Anything failing only in tests → check whether the container actually started, and whether test state leaked between methods.

**Constraint:** **do not touch unrelated code while debugging.** One error, one cause, one fix.

---

## Workflow: E2E Test My App

**Invoke as:** "Run E2E Test My App"

**When invoked**, exercise the full user journey as integration tests, **Testcontainers preferred** (real PostgreSQL and Kafka — see [rules.md §3.2](rules.md#32-integration-tests-for-critical-paths); H2 is not acceptable here).

**If Testcontainers is not set up yet, set it up first as part of this workflow** — add the dependencies, a shared container base class (reuse containers across test classes so the suite stays fast), and dynamic property registration for the datasource and Kafka bootstrap servers.

**The journey:**

| # | Step | Verify |
|---|---|---|
| 1 | Register a user | `201`; password hashed in DB; hash absent from the response |
| 2 | Log in | `200`; JWT returned; token validates on a protected route |
| 3 | Create account A | `201`; balance starts at `0.00`; owned by the caller |
| 4 | Create account B | `201`; second account for the same or a second user |
| 5 | Deposit into A | Balance rises by exactly the deposit; two ledger entries written |
| 6 | Transfer A → B | `201`; A debited and B credited by exactly the amount |
| 7 | **Verify both balances** | Arithmetically correct **and** each reconciles with its ledger entries |
| 8 | Fetch transaction history | All transactions present, correct order, correct directions |
| 9 | **Verify Kafka events** | Exactly one event on `transaction-events` per committed transaction — no more, no fewer |

**Invariant assertions** — run after step 7 and again after step 9, per [prd.md §5](prd.md#5-correctness-guarantee):

- `SUM(ledger_entries.signed_amount) == 0` across the whole system
- every `account.balance == SUM(its ledger entries)`
- no balance `< 0`

**Output format** — **pass/fail per step, not just a final summary:**

```
1. Register user ................... PASS
2. Login (JWT issued) .............. PASS
3. Create account A ................ PASS
...
9. Kafka events published .......... FAIL
   Expected 2 events on transaction-events, found 1.
   Deposit event missing; transfer event present.
   Likely: deposit path not registering the post-commit listener.

Result: 8/9 passed.  Ledger invariant: HOLDS (sum = 0.00)
```

Report failures honestly with the actual output. Never describe a step as passing without running it.

**Note:** Kafka verification (step 9) is only meaningful once Phase 7 is complete. Before that, run steps 1–8 and mark step 9 `SKIPPED (Phase 7 not implemented)` rather than failing it.

---

## Workflow: Clean Up & Refactor Dead Code

**Invoke as:** "Run Clean Up & Refactor Dead Code" — optionally scoped to a module

**When invoked**, scan for:

1. **Unused imports**
2. **Unused private methods and fields** — private scope means usage is provable within the file
3. **Commented-out code blocks** — git history is the archive ([rules.md §4.1](rules.md#41-delete-dead-code-immediately))
4. **`TODO`/`FIXME`/`HACK` comments with no tracked task** — a bare `// TODO: fix this` violates [rules.md §4.3](rules.md#43-no-untracked-todos); the required form is `// TODO(phase-8): ...`
5. **Duplicate logic** extractable into a shared method — flag only genuine repetition of the same rule in multiple places, not incidental similarity
6. Also worth flagging: debug `System.out.println`, unused dependencies in `pom.xml`, unreachable branches, empty `catch` blocks

**Output:** list all findings first, grouped by category, with file and line. For each, state whether removal is **safe** (provably unused) or **needs a decision** (see below).

**Then apply removals only after explicit confirmation.**

**Never silently delete something that may be intentional.** Flag for a decision rather than removing:

- Feature flags and configuration toggles not yet wired up
- Interface methods deliberately unimplemented or unused, kept for a planned implementation
- Public API surface — "unused" may mean "used by a caller outside this scan"
- Anything referenced by reflection, Spring component scanning, JPA, or Jackson (a "unused" no-arg constructor or setter is often required by a framework)
- Test utilities and fixtures used by only one test today
- `@SuppressWarnings`, `@Deprecated`, and annotated-but-idle code — the annotation is usually a signal something knows about it

For these, ask rather than assume. A wrongly deleted feature flag is a much more expensive mistake than a leftover unused method.

**Constraint:** cleanup is its own commit, never mixed with a behaviour change ([rules.md §1.4](rules.md#14-commits-small-focused-one-logical-change)). Run the full test suite after applying removals.

---

## Workflow: Write Clean Git Commits

**Invoke as:** "Run Write Clean Git Commits" — before committing

**When invoked**, read the **staged** diff (`git diff --staged`) and:

1. **Confirm the change is a single logical unit.** If it mixes unrelated changes — a bug fix plus a refactor, two features, a formatting sweep alongside logic — **suggest splitting into multiple commits**, and say concretely which files or hunks belong in which commit.
2. **Write the commit message:**
   - **Imperative mood** — "add", "fix", "remove", not "added"/"adds"/"adding"
   - **Subject line under 72 characters**, no trailing period
   - Scoped prefix matching [rules.md §1.4](rules.md#14-commits-small-focused-one-logical-change): `feat(scope):`, `fix(scope):`, `test(scope):`, `chore(scope):`, `docs(scope):`, `refactor(scope):`
   - **Short body** when the change needs context — explain **why**, since the diff already shows the what. Wrap at 72 characters.
3. **Never write vague messages.** `fixes`, `updates`, `wip`, `changes`, `misc`, `stuff` are all rejected. The message must say **what changed and why**.

```
feat(transfer): post debit and credit rows in one transaction

Balance updates and both ledger entries now share a single
@Transactional boundary, so a failure mid-transfer cannot leave
the sender debited without the receiver credited.
```

4. **Also check before proposing:** no secrets or `.env` in the diff, no debug printlns, no commented-out code, no unrelated formatting noise. Flag any of these instead of committing them.

**Constraints:**

- **Show the proposed message before committing.** Always. No commit happens until it is approved.
- **Never merge or push as part of this workflow** — merging requires separate explicit confirmation per [rules.md §1.3](rules.md#13-never-merge-without-explicit-confirmation).

---

## Workflow: Turn a Task Into a Skill

**Invoke as:** "Run Turn a Task Into a Skill: `<description of the repeated task>`"

**When invoked with a description of a repeated task:**

1. **Extract the general pattern, not the one-off specifics.** Strip out this instance's particular file names, values, and details; keep the repeatable procedure. The test: would this block still be useful next month on a different file? If it names `TransferService` in step 2, it is not general enough yet.
2. **Write it as a reusable instruction block** following the same structure as the workflows in this file:
   - `## Workflow: <Name>`
   - `**Invoke as:**` — how to call it, including any input it takes
   - **When invoked** — numbered steps, in the order they must happen
   - **Output** — the expected output format, with an example where the shape matters
   - **Constraints** — what it must not do (scope limits, confirmation gates)
3. **Ask me to name the workflow before saving.** Propose a name, but do not save until confirmed — the name is how it gets invoked, so it has to be one that comes to mind naturally.
4. **Append it to `memory.md`** under a new heading, after the existing workflows and before the Index. Then add a row to the Index table.

**Constraints:**

- Append only — never rewrite or reorder existing workflows in this file.
- Do not save until the name is confirmed.
- If the new workflow substantially overlaps an existing one, say so and propose extending the existing workflow instead. Two near-duplicate workflows means neither gets used consistently.

---

## Index

| Workflow | Invoke with | Modifies code? |
|---|---|---|
| [Find Security Gaps](#workflow-find-security-gaps) | optional module scope | No — reports only |
| [Debug an Error Fast](#workflow-debug-an-error-fast) | error message / stack trace | Proposes minimal fix |
| [E2E Test My App](#workflow-e2e-test-my-app) | — | Adds/runs tests |
| [Clean Up & Refactor Dead Code](#workflow-clean-up--refactor-dead-code) | optional module scope | Only after confirmation |
| [Write Clean Git Commits](#workflow-write-clean-git-commits) | — (reads staged diff) | Commits after approval |
| [Turn a Task Into a Skill](#workflow-turn-a-task-into-a-skill) | task description | Appends to this file |
