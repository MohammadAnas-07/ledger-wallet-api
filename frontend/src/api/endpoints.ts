/*
 * The API, one function per endpoint.
 *
 * Nothing here does anything except name a path and its types. The point is
 * that no screen ever writes a URL: a path that appears twice is a path that
 * gets changed once.
 *
 * Frozen against the controllers on 2026-08-30 (architecture.md §7).
 */

import { request } from './client'
import type {
  AccountResponse,
  AuthResponse,
  LoginRequest,
  MoneyMovementRequest,
  PageResponse,
  RegisterRequest,
  StatementEntryResponse,
  StatementQuery,
  TransactionDetailResponse,
  TransactionResponse,
  TransferRequest,
  TransferResponse,
  UserResponse,
  Uuid,
} from './types'

/* ---- Auth ---------------------------------------------------------------- */

/**
 * Create a user. Answers 201 with the user and **no token** — registering does
 * not sign anyone in. A registration flow is this call followed by `login`, and
 * the second one can fail on its own.
 */
export function register(body: RegisterRequest): Promise<UserResponse> {
  return request('/auth/register', {
    method: 'POST',
    body,
    authenticated: false,
  })
}

export function login(body: LoginRequest): Promise<AuthResponse> {
  return request('/auth/login', { method: 'POST', body, authenticated: false })
}

/** The caller's own profile. Protected — unlike register and login, which are
 *  the only two public paths in the whole API. */
export function me(): Promise<UserResponse> {
  return request('/auth/me')
}

/* ---- Accounts ------------------------------------------------------------ */

/** Creates a wallet for the caller. No body: an account has no inputs. */
export function createAccount(): Promise<AccountResponse> {
  return request('/accounts', { method: 'POST' })
}

export function listAccounts(): Promise<AccountResponse[]> {
  return request('/accounts')
}

export function getAccount(id: Uuid): Promise<AccountResponse> {
  return request(`/accounts/${id}`)
}

/* ---- Money movement ------------------------------------------------------ */

export function deposit(
  accountId: Uuid,
  body: MoneyMovementRequest,
): Promise<TransactionResponse> {
  return request(`/accounts/${accountId}/deposit`, { method: 'POST', body })
}

export function withdraw(
  accountId: Uuid,
  body: MoneyMovementRequest,
): Promise<TransactionResponse> {
  return request(`/accounts/${accountId}/withdraw`, { method: 'POST', body })
}

/**
 * Transfer between two accounts.
 *
 * Send an `idempotencyKey`. This endpoint can answer 409 under contention, and
 * a retry without a key can move the money a second time; with one, the repeat
 * returns the original result. Use {@link newIdempotencyKey}, and keep the same
 * key across retries of the same intended transfer — a new key on a retry is a
 * new transfer.
 */
export function transfer(body: TransferRequest): Promise<TransferResponse> {
  return request('/transfers', { method: 'POST', body })
}

/** A fresh key for one intended movement of money. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

/* ---- History ------------------------------------------------------------- */

export function getStatement(
  accountId: Uuid,
  query: StatementQuery = {},
): Promise<PageResponse<StatementEntryResponse>> {
  return request(`/accounts/${accountId}/transactions`, { query })
}

export function getTransaction(id: Uuid): Promise<TransactionDetailResponse> {
  return request(`/transactions/${id}`)
}
