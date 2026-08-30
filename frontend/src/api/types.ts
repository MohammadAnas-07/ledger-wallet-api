/*
 * The API contract, mirrored.
 *
 * Every type here corresponds to a record in the backend, and the names match
 * field for field. That is the point: when the backend changes shape, this file
 * is the one place that has to change, and everything reading it stops
 * compiling until it does.
 *
 * Frozen against the controllers and DTOs on 2026-08-30, not against the
 * documentation — see design.md §7.
 */

/**
 * A decimal amount, carried as the exact text the backend sent.
 *
 * Not `number`, deliberately. Amounts are `BigDecimal(19,2)` on the server,
 * which permits seventeen integer digits; a JavaScript number is only exact to
 * about fifteen, so the largest values a balance is allowed to hold would be
 * silently rounded on arrival. Keeping the literal means nothing is lost
 * between the ledger and the screen, and it is the formatter's job — never
 * arithmetic's — to turn it into something a person reads.
 *
 * This layer does no arithmetic on money at all. There is nothing it would need
 * to compute: the backend sends every balance and every running total already
 * worked out.
 */
export type Money = string

export type Uuid = string

/** ISO-8601 instant, e.g. `2026-08-30T09:12:44.113Z`. */
export type IsoInstant = string

export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED'
export type TransactionType = 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER'
export type TransactionStatus = 'COMPLETED' | 'FAILED'
export type EntryDirection = 'DEBIT' | 'CREDIT'

/* ---- Auth ---------------------------------------------------------------- */

export interface RegisterRequest {
  email: string
  password: string
  fullName: string
}

export interface LoginRequest {
  email: string
  password: string
}

export interface AuthResponse {
  accessToken: string
  tokenType: string
  /** A lifetime, not a deadline — the server sends it this way so a client with
   *  a skewed clock can still tell how long it has. */
  expiresInSeconds: number
}

/** Carries no credential material. `POST /register` answers with this and no
 *  token: registering and being logged in are two separate calls. */
export interface UserResponse {
  id: Uuid
  email: string
  fullName: string
  createdAt: IsoInstant
}

/* ---- Accounts ------------------------------------------------------------ */

export interface AccountResponse {
  id: Uuid
  accountNumber: string
  balance: Money
  status: AccountStatus
  createdAt: IsoInstant
}

/* ---- Money movement ------------------------------------------------------ */

export interface MoneyMovementRequest {
  amount: Money
  /** Optional on the wire. Supplying it makes a retry safe: repeating a request
   *  that already succeeded returns the original result instead of moving money
   *  twice. */
  idempotencyKey?: string
}

export interface TransferRequest {
  fromAccountId: Uuid
  toAccountId: Uuid
  amount: Money
  idempotencyKey?: string
}

/** A deposit or withdrawal. Reports only the acted-on account's balance. */
export interface TransactionResponse {
  transactionId: Uuid
  type: TransactionType
  amount: Money
  accountId: Uuid
  balanceAfter: Money
  status: TransactionStatus
  createdAt: IsoInstant
}

/**
 * A transfer.
 *
 * Note what is absent: the recipient's balance. The destination often belongs
 * to someone else, and being able to send them money is not a reason to learn
 * what they hold — so there is no "their balance after" to display, and no UI
 * should leave a space for one.
 */
export interface TransferResponse {
  transactionId: Uuid
  amount: Money
  fromAccountId: Uuid
  toAccountId: Uuid
  fromBalanceAfter: Money
  status: TransactionStatus
  createdAt: IsoInstant
}

/* ---- History ------------------------------------------------------------- */

/** The other account in a transfer. Null when the other side is the system
 *  account — a deposit or withdrawal faces the outside world, and the API does
 *  not name its internal counterparty. */
export interface CounterpartyResponse {
  accountId: Uuid
  accountNumber: string
}

/**
 * One line of a statement.
 *
 * Built from a ledger entry, not a transaction, because direction only means
 * something relative to an account: one transfer is a debit on one statement
 * and a credit on the other.
 */
export interface StatementEntryResponse {
  entryId: Uuid
  transactionId: Uuid
  type: TransactionType
  direction: EntryDirection
  amount: Money
  balanceAfter: Money
  counterparty: CounterpartyResponse | null
  createdAt: IsoInstant
}

/** One side of a transaction. `external` marks the system account, whose id and
 *  number are withheld; the entry still appears so the pair visibly sums to
 *  zero. */
export interface TransactionEntryResponse {
  accountId: Uuid | null
  accountNumber: string | null
  external: boolean
  direction: EntryDirection
  amount: Money
  signedAmount: Money
}

/** Both entries of one transaction. Carries no `balanceAfter`: either party can
 *  read this, and publishing the recorded balances would hand each of them the
 *  other's. */
export interface TransactionDetailResponse {
  id: Uuid
  type: TransactionType
  amount: Money
  status: TransactionStatus
  createdAt: IsoInstant
  entries: TransactionEntryResponse[]
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

// A type alias rather than an interface: only an alias gets the implicit index
// signature the client needs to render this as query parameters.
export type StatementQuery = {
  page?: number
  size?: number
  /** Inclusive ISO-8601 instant. */
  from?: IsoInstant
  /** Inclusive ISO-8601 instant. */
  to?: IsoInstant
}

/* ---- Errors -------------------------------------------------------------- */

/** The one body shape every failed request uses. */
export interface ErrorResponse {
  code: string
  message: string
  timestamp: IsoInstant
  path: string
}
