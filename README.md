# Ledger Wallet API

A wallet/ledger service built with double-entry bookkeeping and optimistic locking for safe concurrent transfers.

Every movement of money — deposit, withdrawal, transfer — writes a transaction header and **exactly two ledger entries**, a debit and a matching credit, inside one database transaction. Three invariants hold at all times, and the test suite asserts them as post-conditions on every money-movement test:

1. All ledger entries ever written sum to exactly zero
2. Every account's stored balance equals the sum of its own entries
3. No user account is ever negative

## Tech Stack

Java 21 · Spring Boot 3.4 · PostgreSQL 16 · Apache Kafka · Flyway · Docker Compose · JWT (HS256) · Bucket4j

## Running Locally

### Prerequisites

- Docker Desktop (or any Docker engine) with Compose
- For running the test suite: JDK 21 and Maven — the tests start their own containers via Testcontainers

### 1. Create your `.env`

```bash
cp .env.example .env
```

Then fill it in. `.env` is gitignored and must never be committed; `application.yml` deliberately has **no fallback defaults** for secrets, so the application refuses to start rather than run on a guessable key.

| Variable | What it is |
|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Database name and credentials |
| `DB_HOST_PORT` | Host port for the database container. Change it if something already holds 5432 — a native PostgreSQL install, for instance |
| `SERVER_PORT` | Host port the API is published on. Change it if 8080 is taken |
| `JWT_SECRET` | Token signing key, **at least 32 bytes**. The app fails at startup below that |
| `JWT_EXPIRATION_MINUTES` | Token lifetime, 15 by default |

Generate a signing key with:

```bash
openssl rand -base64 48
```

### 2. Start everything

```bash
docker compose up --build -d
```

Four services come up: the API, PostgreSQL, Kafka and ZooKeeper. The API waits for the database and broker to report healthy before it starts, and Flyway applies the migrations on first boot.

Watch it come up:

```bash
docker compose logs -f app
```

### 3. Check it is alive

```bash
curl http://localhost:8080/health
```

```json
{"status":"UP"}
```

If you changed `SERVER_PORT`, use that port here and everywhere below.

### Stopping

```bash
docker compose stop     # keep the data
docker compose down -v  # remove the containers and the database volume
```

## Running the Tests

```bash
mvn verify
```

Unit tests run first, then the integration tests, which start a **real PostgreSQL and a real Kafka** through Testcontainers — no H2. In-memory databases do not reproduce MVCC and row locking, so a green concurrency test there would prove nothing about the thing this project exists to guarantee.

Unit tests alone, no Docker needed:

```bash
mvn test
```

> **Give Docker room.** The integration tests start their own database and broker. If the Compose stack is running at the same time on a machine with a small Docker memory allowance, Kafka can fail to start within its timeout and every integration test errors out at container startup. Run `docker compose stop` first, or give Docker more memory.

## A Walk Through the API

Every step below is a real request. Replace the port if you changed `SERVER_PORT`.

### 1. Register

```bash
curl -X POST http://localhost:8080/api/v1/auth/register -H "Content-Type: application/json" -d '{"email":"alice@example.com","password":"a-sufficiently-long-password","fullName":"Alice"}'
```

`201` with the new user. The password is BCrypt-hashed at strength 12 and never appears in a response, a log line, or a `toString()`.

### 2. Log in

```bash
curl -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"email":"alice@example.com","password":"a-sufficiently-long-password"}'
```

`200` with `accessToken`. Copy it:

```bash
TOKEN=<paste the accessToken>
```

A wrong password and an unknown email return the **same** generic `401`, so the response cannot be used to discover which addresses are registered.

### 3. Create a wallet

```bash
curl -X POST http://localhost:8080/api/v1/accounts -H "Authorization: Bearer $TOKEN"
```

`201`, balance `0.00`. A user may hold several wallets. The owner is always taken from the token — no endpoint accepts a user id from the client.

```bash
ACCOUNT=<paste the account id>
```

### 4. Deposit

```bash
curl -X POST http://localhost:8080/api/v1/accounts/$ACCOUNT/deposit -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"amount":"150.00","idempotencyKey":"demo-deposit-1"}'
```

`201`, balance `150.00`. Send the identical request again and you get the **same transaction back** — the money moves once. Send it with a different amount and the same key and it is refused with `409 IDEMPOTENCY_KEY_REUSED`, because replaying it would report a movement you never asked for as though it had just happened.

### 5. Transfer

Register a second user, create their wallet, then:

```bash
curl -X POST http://localhost:8080/api/v1/transfers -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"fromAccountId":"'$ACCOUNT'","toAccountId":"<bob-account-id>","amount":"40.00","idempotencyKey":"demo-transfer-1"}'
```

`201`. The debit, the credit and both balance updates commit together or not at all. You may credit anyone; you may only debit an account you own. The response reports **your** resulting balance only — being able to send someone money is not a reason to learn what they hold.

### 6. Read the statement

```bash
curl "http://localhost:8080/api/v1/accounts/$ACCOUNT/transactions?page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

Newest first, paginated, optionally filtered with `from` and `to` (ISO-8601 instants). Asking for someone else's statement returns `403`.

### 7. See the rate limiter refuse you

`login` allows 10 attempts per minute per client address, and the bucket refills
**continuously** — one token roughly every six seconds — rather than resetting on a
minute boundary. So a slow sequential loop never sees a `429`: at more than about 1.1
seconds per request, the bucket hands tokens back as fast as you spend them. Send a
burst instead:

```bash
for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code}
" -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"email":"nobody@example.com","password":"wrong-password-here"}' & done | sort | uniq -c; wait
```

About ten `401`s and the rest `429`. Sequentially it takes more than twelve requests to
see one, because of that refill — 20 is a safe number:

```bash
for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"email":"nobody@example.com","password":"wrong-password-here"}'; done; echo
```

A refusal carries `Retry-After` and the standard error body:

```bash
curl -i -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d '{"email":"nobody@example.com","password":"wrong-password-here"}' | head -12
```

### 8. Watch the events

Every committed transaction publishes one event to the `transaction-events` topic after the commit, and an audit consumer records it. A rolled-back transaction publishes nothing.

```bash
docker compose exec kafka kafka-console-consumer --bootstrap-server localhost:29092 --topic transaction-events --from-beginning
```

## API Summary

| Method | Path | Description | Auth |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Create a user | public, rate limited |
| `POST` | `/api/v1/auth/login` | Exchange credentials for a JWT | public, rate limited |
| `GET` | `/api/v1/auth/me` | The caller's own profile | required |
| `POST` | `/api/v1/accounts` | Create a wallet | required |
| `GET` | `/api/v1/accounts` | List the caller's wallets | required |
| `GET` | `/api/v1/accounts/{id}` | One wallet | required, owner only |
| `POST` | `/api/v1/accounts/{id}/deposit` | Pay in | required, owner only |
| `POST` | `/api/v1/accounts/{id}/withdraw` | Pay out | required, owner only |
| `POST` | `/api/v1/transfers` | Move money between accounts | required, owner of the source |
| `GET` | `/api/v1/accounts/{id}/transactions` | Paginated statement | required, owner only |
| `GET` | `/api/v1/transactions/{id}` | One transaction with both entries | required, either party |
| `GET` | `/health` | Liveness, for the Docker health check | public |

Every path except `register`, `login` and `/health` requires a bearer token — the filter chain ends in `.anyRequest().authenticated()`, so a newly added endpoint is protected unless someone deliberately opens it.

Errors all share one shape:

```json
{
  "code": "INSUFFICIENT_FUNDS",
  "message": "Account balance is insufficient for this operation",
  "timestamp": "2026-08-29T10:15:30Z",
  "path": "/api/v1/accounts/a1.../withdraw"
}
```

| Status | Code | Meaning |
|---|---|---|
| `400` | `VALIDATION_ERROR` | Malformed or invalid request |
| `400` | `SELF_TRANSFER_NOT_ALLOWED` | Source and destination were the same account |
| `401` | `INVALID_CREDENTIALS` | A failed login. A request with a missing, invalid or expired token gets a bare `401` with no body, straight from the filter chain |
| `403` | `FORBIDDEN` | Authenticated, but the resource is not yours |
| `404` | `ACCOUNT_NOT_FOUND` / `TRANSACTION_NOT_FOUND` / `NOT_FOUND` | No such thing |
| `405` | `METHOD_NOT_ALLOWED` | The path exists, the method does not |
| `409` | `EMAIL_ALREADY_REGISTERED` | That address is taken |
| `409` | `CONCURRENT_MODIFICATION` | Lock conflict — safe to retry, ideally with an idempotency key |
| `409` | `IDEMPOTENCY_KEY_REUSED` | Your key was sent again for a different request |
| `422` | `INSUFFICIENT_FUNDS` | Refused on a business rule; **no ledger entry was written** |
| `429` | `RATE_LIMIT_EXCEEDED` | Too many requests to a public auth endpoint; carries `Retry-After` |

## Notes on the Design

- **Rate limiting.** `register` and `login` are throttled per client address (5/min and 10/min), because they are the only paths reachable without a token. The limiter runs ahead of authentication, so a throttled request costs no database read and no BCrypt comparison. The limit is a **sustained rate**, not a hard cap per calendar minute: tokens refill continuously, so a burst is refused while a slow trickle is not. That is the intended shape — brute force is a burst.
- **Idempotency keys are yours.** A key is scoped to the caller who chose it — the unique index is `(initiated_by, idempotency_key)`. Two people may pick the same string without ever meeting.
- **Concurrency.** Accounts carry a `@Version` and conflicts fail loudly rather than losing an update. Transfers retry a bounded number of times, which is only safe because of the idempotency key. Balance updates are applied in a fixed account order so two transfers in opposite directions cannot deadlock.
- **The system account is the exception.** Every deposit and withdrawal posts its counter-entry against one seeded row, so it is a genuine hotspot: under twelve concurrent depositors — each into a different account — 87% of deposits were failing on it. It is now locked with `PESSIMISTIC_WRITE`, which took that to 0%. Everything else stays optimistic. The measurements are in [architecture.md §3](architecture.md#3-concurrency-strategy--optimistic-locking-via-version) and the load test that produced them is `TransferLoadIT`.

## Known Limitations

Honest ones, not hypothetical:

- **Rate limiting is per instance.** Buckets live in memory, so behind N replicas the effective allowance is N times the configured one. A shared backend (bucket4j has Redis and Hazelcast modules) would be needed for a real deployment.
- **Tokens cannot be revoked.** Nothing consults the database on validation, so a leaked token stays usable until it expires. The 15-minute lifetime is the whole mitigation, and a password change does not invalidate tokens already issued.
- **Registration reveals whether an address is registered.** A duplicate returns `409`, which login deliberately avoids doing. Rate limiting bounds how fast that can be walked, but does not remove it.
- **A committed transaction can go unpublished.** Events are sent after the commit, so if the broker is unreachable at that moment the movement is recorded but the event is lost. This is the safe direction — a missing audit record can be rebuilt from the ledger, a fabricated one cannot be detected — but a transactional outbox would close it.
- **The system account still serialises.** Pessimistic locking removed the wasted rollbacks, not the fact that every deposit and withdrawal queues on one row. If that becomes the ceiling, the next step is removing the shared row: shard it, or derive its balance from its entries instead of storing one.

## Project Docs

- [Product Requirements](prd.md)
- [Architecture](architecture.md)
- [Development Rules](rules.md)
- [Phased Roadmap](phases.md)
- [Design Brief (future frontend)](design.md)
- [Reusable Workflows](memory.md)

## Contact

[github.com/MohammadAnas-07](https://github.com/MohammadAnas-07)
