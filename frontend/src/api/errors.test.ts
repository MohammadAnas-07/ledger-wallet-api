import { describe, expect, it } from 'vitest'

import { ApiError, fieldErrors, toApiErrorCode, userMessage } from './errors'

describe('toApiErrorCode', () => {
  it('accepts the codes the backend actually emits', () => {
    expect(toApiErrorCode('INSUFFICIENT_FUNDS')).toBe('INSUFFICIENT_FUNDS')
  })

  it('refuses anything else, rather than trusting the wire', () => {
    expect(toApiErrorCode('SOMETHING_NEW')).toBeNull()
    expect(toApiErrorCode(42)).toBeNull()
    expect(toApiErrorCode(undefined)).toBeNull()
  })
})

describe('isRetryable', () => {
  it('treats a lock conflict as retryable, because nothing was written', () => {
    const error = new ApiError({ code: 'CONCURRENT_MODIFICATION', status: 409 })
    expect(error.isRetryable).toBe(true)
  })

  it('does not offer a retry that would fail identically', () => {
    // The balance has to change first; retrying is just a second refusal.
    expect(
      new ApiError({ code: 'INSUFFICIENT_FUNDS', status: 422 }).isRetryable,
    ).toBe(false)
    expect(
      new ApiError({ code: 'INVALID_CREDENTIALS', status: 401 }).isRetryable,
    ).toBe(false)
  })
})

describe('userMessage', () => {
  it('does not read a lock conflict as a failure', () => {
    const message = userMessage(
      new ApiError({ code: 'CONCURRENT_MODIFICATION', status: 409 }),
    )
    expect(message).toBe('That did not go through. Try again.')
  })

  it('counts down a rate limit when Retry-After said how long', () => {
    expect(
      userMessage(
        new ApiError({
          code: 'RATE_LIMIT_EXCEEDED',
          status: 429,
          retryAfterSeconds: 6,
        }),
      ),
    ).toBe('Too many attempts. Try again in 6 seconds.')

    expect(
      userMessage(
        new ApiError({
          code: 'RATE_LIMIT_EXCEEDED',
          status: 429,
          retryAfterSeconds: 1,
        }),
      ),
    ).toBe('Too many attempts. Try again in 1 second.')
  })

  it('falls back when Retry-After was missing or unparseable', () => {
    expect(
      userMessage(new ApiError({ code: 'RATE_LIMIT_EXCEEDED', status: 429 })),
    ).toBe('Too many attempts. Wait a moment and try again.')
  })

  it('never repeats the server wording', () => {
    // "No such account" is written for a log, not for a person.
    const error = new ApiError({
      code: 'ACCOUNT_NOT_FOUND',
      status: 404,
      serverMessage: 'No such account',
    })
    expect(userMessage(error)).toBe('That account no longer exists.')
    expect(error.serverMessage).toBe('No such account')
  })
})

describe('fieldErrors', () => {
  it('splits the backend format back into fields', () => {
    // GlobalExceptionHandler joins field errors as "field: message" with "; ".
    const error = new ApiError({
      code: 'VALIDATION_ERROR',
      status: 400,
      serverMessage:
        'email: Email must be a valid address; password: Password must be between 12 and 72 characters',
    })

    expect(fieldErrors(error)).toEqual({
      email: 'Email must be a valid address',
      password: 'Password must be between 12 and 72 characters',
    })
  })

  it('keeps the first message when one field failed twice', () => {
    const error = new ApiError({
      code: 'VALIDATION_ERROR',
      status: 400,
      serverMessage: 'password: Password is required; password: too short',
    })
    expect(fieldErrors(error).password).toBe('Password is required')
  })

  it('does not swallow a message that names no field', () => {
    // e.g. "Request body is missing or malformed".
    const error = new ApiError({
      code: 'VALIDATION_ERROR',
      status: 400,
      serverMessage: 'Request body is missing or malformed',
    })
    expect(fieldErrors(error)).toEqual({
      '': 'Request body is missing or malformed',
    })
  })

  it('is empty for anything that is not a validation failure', () => {
    expect(
      fieldErrors(new ApiError({ code: 'FORBIDDEN', status: 403 })),
    ).toEqual({})
  })
})
