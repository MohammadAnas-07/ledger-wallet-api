/*
 * Every way a request can fail, as one type.
 *
 * The backend answers every failure with the same body — code, message,
 * timestamp, path — so the codes below are exhaustive over what it emits
 * (architecture.md §7). Three more are added here for failures that never reach
 * the backend at all: the network being down, the response not being JSON, and
 * a status nothing accounts for.
 */

import type { ErrorResponse } from './types'

export type ApiErrorCode =
  // Emitted by the backend
  | 'VALIDATION_ERROR'
  | 'SELF_TRANSFER_NOT_ALLOWED'
  | 'INVALID_CREDENTIALS'
  | 'EMAIL_ALREADY_REGISTERED'
  | 'FORBIDDEN'
  | 'ACCOUNT_NOT_FOUND'
  | 'TRANSACTION_NOT_FOUND'
  | 'NOT_FOUND'
  | 'METHOD_NOT_ALLOWED'
  | 'CONCURRENT_MODIFICATION'
  | 'IDEMPOTENCY_KEY_REUSED'
  | 'INSUFFICIENT_FUNDS'
  | 'RATE_LIMIT_EXCEEDED'
  | 'INTERNAL_ERROR'
  // Produced here
  | 'UNAUTHENTICATED'
  | 'NETWORK_ERROR'
  | 'UNEXPECTED_RESPONSE'

export class ApiError extends Error {
  readonly code: ApiErrorCode
  readonly status: number
  /** What the server said, kept verbatim for logs and for the one case where it
   *  is worth showing: validation, where it names the offending fields. */
  readonly serverMessage: string | null
  /** Seconds to wait, from `Retry-After`. Only ever set on a rate limit. */
  readonly retryAfterSeconds: number | null

  constructor(init: {
    code: ApiErrorCode
    status: number
    serverMessage?: string | null
    retryAfterSeconds?: number | null
  }) {
    super(init.serverMessage ?? init.code)
    this.name = 'ApiError'
    this.code = init.code
    this.status = init.status
    this.serverMessage = init.serverMessage ?? null
    this.retryAfterSeconds = init.retryAfterSeconds ?? null
  }

  /**
   * Whether repeating the identical request is a reasonable thing to offer.
   *
   * `409 CONCURRENT_MODIFICATION` is the one that matters. It is not a failure
   * in any sense the user caused — two writes touched one account at once, one
   * of them lost, and nothing was written. It should read as "that didn't go
   * through, try again", never as a red error, and the backend produces it by
   * design under load.
   *
   * `INSUFFICIENT_FUNDS` is deliberately not retryable: the same request will
   * fail identically until the balance changes.
   */
  get isRetryable(): boolean {
    return (
      this.code === 'CONCURRENT_MODIFICATION' ||
      this.code === 'NETWORK_ERROR' ||
      this.code === 'INTERNAL_ERROR'
    )
  }
}

const BACKEND_CODES = new Set<string>([
  'VALIDATION_ERROR',
  'SELF_TRANSFER_NOT_ALLOWED',
  'INVALID_CREDENTIALS',
  'EMAIL_ALREADY_REGISTERED',
  'FORBIDDEN',
  'ACCOUNT_NOT_FOUND',
  'TRANSACTION_NOT_FOUND',
  'NOT_FOUND',
  'METHOD_NOT_ALLOWED',
  'CONCURRENT_MODIFICATION',
  'IDEMPOTENCY_KEY_REUSED',
  'INSUFFICIENT_FUNDS',
  'RATE_LIMIT_EXCEEDED',
  'INTERNAL_ERROR',
])

/** Narrow a code off the wire to one this app knows, without trusting it. */
export function toApiErrorCode(code: unknown): ApiErrorCode | null {
  return typeof code === 'string' && BACKEND_CODES.has(code)
    ? (code as ApiErrorCode)
    : null
}

export function isErrorResponse(body: unknown): body is ErrorResponse {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as ErrorResponse).code === 'string' &&
    typeof (body as ErrorResponse).message === 'string'
  )
}

/*
 * What the user is told.
 *
 * The server's own messages are written for whoever is reading the logs — "No
 * such account", "Optimistic lock conflict" — so the UI does not repeat them.
 * The one exception is VALIDATION_ERROR, handled separately below, where the
 * server message names the field that is actually wrong.
 */
const MESSAGES: Record<ApiErrorCode, string> = {
  VALIDATION_ERROR: 'Check the details above and try again.',
  SELF_TRANSFER_NOT_ALLOWED: 'Choose a different account to send to.',
  INVALID_CREDENTIALS: 'That email and password do not match.',
  EMAIL_ALREADY_REGISTERED: 'That email is already registered.',
  FORBIDDEN: 'That account is not yours.',
  ACCOUNT_NOT_FOUND: 'That account no longer exists.',
  TRANSACTION_NOT_FOUND: 'That transaction no longer exists.',
  NOT_FOUND: 'That page could not be found.',
  METHOD_NOT_ALLOWED: 'Something went wrong. Try again.',
  // Not phrased as a failure, because it is not one: nothing was written, and
  // the same request sent again will normally succeed.
  CONCURRENT_MODIFICATION: 'That did not go through. Try again.',
  IDEMPOTENCY_KEY_REUSED: 'That looks like a different request. Start it again.',
  INSUFFICIENT_FUNDS: 'Not enough in this account.',
  RATE_LIMIT_EXCEEDED: 'Too many attempts. Wait a moment and try again.',
  INTERNAL_ERROR: 'Something went wrong at our end. Try again.',
  UNAUTHENTICATED: 'Your session has expired. Sign in again.',
  NETWORK_ERROR: 'Cannot reach the server. Check your connection.',
  UNEXPECTED_RESPONSE: 'Something went wrong. Try again.',
}

/** One line, safe to put in front of a person. */
export function userMessage(error: ApiError): string {
  if (error.code === 'RATE_LIMIT_EXCEEDED' && error.retryAfterSeconds !== null) {
    const seconds = error.retryAfterSeconds
    return `Too many attempts. Try again in ${seconds} second${seconds === 1 ? '' : 's'}.`
  }
  return MESSAGES[error.code]
}

/**
 * Split a validation message back into per-field errors.
 *
 * The backend joins field errors as `field: message`, separated by `; `. Taking
 * it apart again is what lets a form put each message under the input that
 * caused it, instead of stacking a paragraph above the whole thing.
 *
 * Anything that does not fit that shape — the malformed-body message, a type
 * mismatch on a query parameter — comes back under the empty key, so it is
 * still shown somewhere rather than swallowed.
 */
export function fieldErrors(error: ApiError): Record<string, string> {
  if (error.code !== 'VALIDATION_ERROR' || !error.serverMessage) {
    return {}
  }

  const result: Record<string, string> = {}
  for (const part of error.serverMessage.split('; ')) {
    const separator = part.indexOf(': ')
    if (separator === -1) {
      result[''] = part
      continue
    }
    const field = part.slice(0, separator)
    // First one wins: a field with two failed constraints has one input to
    // point at, and the first message is as good as the second.
    if (!(field in result)) {
      result[field] = part.slice(separator + 2)
    }
  }
  return result
}
