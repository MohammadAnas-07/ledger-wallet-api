# Ledger Wallet API

[![CI](https://github.com/MohammadAnas-07/ledger-wallet-api/actions/workflows/ci.yml/badge.svg)](https://github.com/MohammadAnas-07/ledger-wallet-api/actions/workflows/ci.yml)

A wallet and ledger API built with double-entry bookkeeping, idempotency keys, and optimistic locking to handle concurrent money transfers safely.

Every deposit, withdrawal, and transfer creates one transaction and exactly two ledger entries: one debit and one matching credit. Both entries are written inside the same database transaction.

The system maintains three invariants:

1. The sum of all ledger entries is always zero.
2. Each account's stored balance matches the sum of its ledger entries.
3. User accounts cannot have negative balances.

The test suite checks these invariants after every money-movement test.

## Tech stack

Java 21 · Spring Boot 3.4 · PostgreSQL 16 · Apache Kafka · Flyway · Docker Compose · JWT (HS256) · Bucket4j

## Running locally

### Prerequisites

* Docker Desktop, or another Docker engine with Compose
* JDK 21 and Maven for running the test suite

Integration tests use Testcontainers to start PostgreSQL and Kafka automatically.

### 1. Create the `.env` file

```bash
cp .env.example .env
```

Fill in the required values.

The `.env` file is gitignored and should never be committed. `application.yml` does not provide fallback values for secrets, so the application fails to start if required configuration is missing.

| Variable                                              | Description                                                        |
| ----------------------------------------------------- | ------------------------------------------------------------------ |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Database name and credentials                                      |
| `DB_HOST_PORT`                                        | Host port for PostgreSQL. Change it if port 5432 is already in use |
| `SERVER_PORT`                                         | Port exposed by the API. Change it if 8080 is already in use       |
| `JWT_SECRET`                                          | JWT signing key, minimum 32 bytes                                  |
| `JWT_EXPIRATION_MINUTES`                              | Token lifetime, 15 minutes by default                              |

Generate a JWT signing key with:

```bash
openssl rand -base64 48
```

### 2. Start the application

```bash
docker compose up --build -d
```

This starts:

* Spring Boot API
* PostgreSQL
* Kafka
* ZooKeeper

The API waits for PostgreSQL and Kafka to become healthy before starting. Flyway applies database migrations on startup.

Watch the application logs:

```bash
docker compose logs -f app
```

### 3. Check the API

```bash
curl http://localhost:8080/health
```

Expected response:

```json
{"status":"UP"}
```

If you changed `SERVER_PORT`, use that port instead of `8080`.

### Stop the application

Keep containers and database data:

```bash
docker compose stop
```

Remove containers and database volumes:

```bash
docker compose down -v
```

## Running tests

Run the complete test suite:

```bash
mvn verify
```

Unit tests run first. Integration tests then start real PostgreSQL and Kafka containers through Testcontainers.

The project does not use H2 for concurrency tests because in-memory databases do not reproduce PostgreSQL MVCC behavior or row-locking behavior accurately.

Run only unit tests:

```bash
mvn test
```

> The integration tests start PostgreSQL and Kafka containers. If the Docker Compose stack is already running and Docker has limited memory available, Kafka may fail to start before the Testcontainers timeout. Stop the Compose stack first or increase Docker's memory allocation.

# API walkthrough

The examples below use port `8080`. Replace it if you changed `SERVER_PORT`.

## 1. Register

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email":"alice@example.com",
    "password":"a-sufficiently-long-password",
    "fullName":"Alice"
  }'
```

Returns `201 Created`.

Passwords are hashed with BCrypt using strength 12. Passwords are never returned in API responses, logs, or `toString()` output.

## 2. Log in

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email":"alice@example.com",
    "password":"a-sufficiently-long-password"
  }'
```

Returns `200 OK` with an `accessToken`.

Save the token:

```bash
TOKEN=<paste-the-access-token>
```

Unknown emails and incorrect passwords return the same generic `401` response. This prevents the login endpoint from being used to discover which email addresses are registered.

## 3. Create a wallet

```bash
curl -X POST http://localhost:8080/api/v1/accounts \
  -H "Authorization: Bearer $TOKEN"
```

Returns `201 Created` with a balance of `0.00`.

A user can own multiple wallets. The account owner is always determined from the JWT, so clients cannot create wallets for arbitrary user IDs.

Save the account ID:

```bash
ACCOUNT=<paste-account-id>
```

## 4. Deposit money

```bash
curl -X POST http://localhost:8080/api/v1/accounts/$ACCOUNT/deposit \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "amount":"150.00",
    "idempotencyKey":"demo-deposit-1"
  }'
```

Returns `201 Created` and the balance becomes `150.00`.

Sending the exact same request again returns the original transaction instead of moving the money twice.

Reusing the same idempotency key with different request data returns:

```text
409 IDEMPOTENCY_KEY_REUSED
```

## 5. Transfer money

Register a second user and create their wallet.

Then send a transfer:

```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "fromAccountId":"'$ACCOUNT'",
    "toAccountId":"<bob-account-id>",
    "amount":"40.00",
    "idempotencyKey":"demo-transfer-1"
  }'
```

Returns `201 Created`.

The debit entry, credit entry, and balance updates either commit together or roll back together.

A user can only debit an account they own. Transfers can credit another user's account.

The response returns the caller's resulting balance and does not expose the recipient's balance.

## 6. Read an account statement

```bash
curl "http://localhost:8080/api/v1/accounts/$ACCOUNT/transactions?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"
```

Transactions are returned newest first and support pagination.

Results can also be filtered using `from` and `to` ISO-8601 timestamps.

Requests for another user's account statement return `403 Forbidden`.

## 7. Test the rate limiter

The login endpoint allows 10 attempts per minute per client IP address.

The Bucket4j bucket refills continuously rather than resetting at the start of each minute. A slow sequence of requests may therefore avoid `429` responses.

Send a burst of requests:

```bash
for i in $(seq 1 20); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST http://localhost:8080/api/v1/auth/login \
    -H "Content-Type: application/json" \
    -d '{"email":"nobody@example.com","password":"wrong-password-here"}' &
done | sort | uniq -c

wait
```

You should see roughly ten `401` responses followed by `429 Too Many Requests`.

A rate-limited response includes the `Retry-After` header.

## 8. Watch transaction events

Every committed transaction publishes an event to the `transaction-events` Kafka topic. An audit consumer records the event.

Rolled-back transactions do not publish events.

Start a Kafka consumer:

```bash
docker compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:29092 \
  --topic transaction-events \
  --from-beginning
```

# API summary

| Method | Path                                 | Description                              | Auth                   |
| ------ | ------------------------------------ | ---------------------------------------- | ---------------------- |
| `POST` | `/api/v1/auth/register`              | Create a user                            | Public, rate limited   |
| `POST` | `/api/v1/auth/login`                 | Exchange credentials for a JWT           | Public, rate limited   |
| `GET`  | `/api/v1/auth/me`                    | Get the caller's profile                 | Required               |
| `POST` | `/api/v1/accounts`                   | Create a wallet                          | Required               |
| `GET`  | `/api/v1/accounts`                   | List the caller's wallets                | Required               |
| `GET`  | `/api/v1/accounts/{id}`              | Get a wallet                             | Required, owner only   |
| `POST` | `/api/v1/accounts/{id}/deposit`      | Deposit money                            | Required, owner only   |
| `POST` | `/api/v1/accounts/{id}/withdraw`     | Withdraw money                           | Required, owner only   |
| `POST` | `/api/v1/transfers`                  | Transfer money between accounts          | Required, source owner |
| `GET`  | `/api/v1/accounts/{id}/transactions` | Get a paginated statement                | Required, owner only   |
| `GET`  | `/api/v1/transactions/{id}`          | Get a transaction and its ledger entries | Required, either party |
| `GET`  | `/health`                            | Liveness check                           | Public                 |

Every endpoint except `register`, `login`, and `/health` requires authentication.

The Spring Security configuration ends with:

```java
.anyRequest().authenticated()
```

That means new endpoints are protected by default unless they are explicitly configured as public.

# Error responses

Application errors use a consistent response format:

```json
{
  "code": "INSUFFICIENT_FUNDS",
  "message": "Account balance is insufficient for this operation",
  "timestamp": "2026-08-29T10:15:30Z",
  "path": "/api/v1/accounts/a1.../withdraw"
}
```

| Status | Code                                                        | Meaning                                              |
| ------ | ----------------------------------------------------------- | ---------------------------------------------------- |
| `400`  | `VALIDATION_ERROR`                                          | Invalid or malformed request                         |
| `400`  | `SELF_TRANSFER_NOT_ALLOWED`                                 | Source and destination are the same account          |
| `401`  | `INVALID_CREDENTIALS`                                       | Login failed                                         |
| `403`  | `FORBIDDEN`                                                 | Authenticated but not allowed to access the resource |
| `404`  | `ACCOUNT_NOT_FOUND` / `TRANSACTION_NOT_FOUND` / `NOT_FOUND` | Resource does not exist                              |
| `405`  | `METHOD_NOT_ALLOWED`                                        | The path exists but the HTTP method is not supported |
| `409`  | `EMAIL_ALREADY_REGISTERED`                                  | Email is already registered                          |
| `409`  | `CONCURRENT_MODIFICATION`                                   | Optimistic locking conflict                          |
| `409`  | `IDEMPOTENCY_KEY_REUSED`                                    | Idempotency key reused with different request data   |
| `422`  | `INSUFFICIENT_FUNDS`                                        | Business rule rejected the operation                 |
| `429`  | `RATE_LIMIT_EXCEEDED`                                       | Too many requests                                    |

Requests with missing, invalid, or expired JWTs receive a bare `401` response directly from the security filter chain.

Failed money operations do not write ledger entries.

# Design decisions

## Rate limiting

`register` and `login` are rate limited per client IP address because they are the only public authentication endpoints.

The rate limiter runs before authentication. A request that exceeds the limit is rejected before the application performs a database lookup or BCrypt password comparison.

The limits represent sustained rates rather than fixed calendar-minute counters because tokens refill continuously.

## Idempotency keys

Idempotency keys are scoped to the user who created them.

The unique constraint is:

```text
(initiated_by, idempotency_key)
```

Two different users can use the same key without conflicting.

## Concurrency

Accounts use `@Version` for optimistic locking.

Conflicts fail instead of silently overwriting another update.

Transfers retry a bounded number of times. Retrying is safe because transfer requests use idempotency keys.

Account updates are processed in a fixed order to prevent deadlocks when transfers occur in opposite directions.

## System account

Deposits and withdrawals post their counter-entry against a seeded system account.

That account became a concurrency hotspot.

During testing with twelve concurrent depositors, each depositing into a different account, 87% of deposits failed because of conflicts on the shared system account.

The system account now uses `PESSIMISTIC_WRITE`, which reduced those failures to 0%.

The rest of the account model continues to use optimistic locking.

See [architecture.md §3](architecture.md#3-concurrency-strategy--optimistic-locking-via-version) and `TransferLoadIT` for the measurements and load test.

# Known limitations

These are current limitations of the implementation.

## Rate limiting is per instance

Rate-limit buckets are stored in memory.

With multiple application replicas, the effective allowance becomes approximately the configured limit multiplied by the number of replicas.

A production deployment would need a shared backend such as Redis or Hazelcast.

## JWTs cannot be revoked

Token validation does not check the database.

A leaked token remains valid until it expires. Tokens currently expire after 15 minutes, and changing a password does not invalidate tokens that were already issued.

## Registration reveals existing email addresses

Duplicate registration requests return `409`.

The login endpoint avoids this behavior by returning the same response for unknown emails and incorrect passwords.

Rate limiting reduces the speed of enumeration attempts but does not eliminate the issue.

## Kafka events can be lost after a successful transaction

Events are published after the database transaction commits.

If Kafka is unavailable at that moment, the financial transaction remains committed but its event may not be published.

The ledger remains the source of truth and missing audit events can be reconstructed from it.

A transactional outbox would remove this gap.

## The system account serializes deposits and withdrawals

Pessimistic locking removed failed retries caused by concurrent updates, but deposits and withdrawals still queue behind the shared system account.

If this becomes a throughput bottleneck, possible next steps include sharding the system account or deriving its balance from ledger entries instead of storing a mutable balance.

# Project documentation

* [Product Requirements](prd.md)
* [Architecture](architecture.md)
* [Development Rules](rules.md)
* [Phased Roadmap](phases.md)
* [Frontend Design Brief](design.md)
* [Reusable Workflows](memory.md)

# Contact

[github.com/MohammadAnas-07](https://github.com/MohammadAnas-07)
