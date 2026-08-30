import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { request } from './client'
import { ApiError } from './errors'
import { clearToken, onSessionExpired, readToken, writeToken } from './session'

function respond(
  status: number,
  body: string,
  headers: Record<string, string> = {},
) {
  // 204 forbids a body outright, and the filter chain's bare 401 has none
  // either — so an empty string here means a genuinely bodyless response.
  return new Response(body === '' ? null : body, { status, headers })
}

function jsonError(code: string, message = 'because') {
  return JSON.stringify({
    code,
    message,
    timestamp: '2026-08-30T09:00:00Z',
    path: '/api/v1/whatever',
  })
}

const fetchMock = vi.fn()

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  fetchMock.mockReset()
  clearToken()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the bearer token', () => {
  it('is attached to authenticated requests', async () => {
    writeToken('a-token')
    fetchMock.mockResolvedValue(respond(200, '[]'))

    await request('/accounts')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/v1/accounts')
    expect(init.headers.Authorization).toBe('Bearer a-token')
  })

  it('is not attached to the two public endpoints', async () => {
    writeToken('a-token')
    fetchMock.mockResolvedValue(respond(200, '{}'))

    await request('/auth/login', {
      method: 'POST',
      body: {},
      authenticated: false,
    })

    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined()
  })

  it('fails an authenticated request with no token, without a round trip', async () => {
    await expect(request('/accounts')).rejects.toMatchObject({
      code: 'UNAUTHENTICATED',
    })
    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('401 means two different things', () => {
  it('ends the session on a bare 401 from the filter chain', async () => {
    writeToken('expired-token')
    const expired = vi.fn()
    const unsubscribe = onSessionExpired(expired)
    // The filter chain returns 401 with no body at all.
    fetchMock.mockResolvedValue(respond(401, ''))

    await expect(request('/accounts')).rejects.toMatchObject({
      code: 'UNAUTHENTICATED',
    })

    expect(expired).toHaveBeenCalledOnce()
    expect(readToken()).toBeNull()
    unsubscribe()
  })

  it('does not end a session when login says the password was wrong', async () => {
    // There is no session to end, and clearing one would sign out a user who
    // mistyped a password in a second tab.
    const expired = vi.fn()
    const unsubscribe = onSessionExpired(expired)
    fetchMock.mockResolvedValue(respond(401, jsonError('INVALID_CREDENTIALS')))

    await expect(
      request('/auth/login', {
        method: 'POST',
        body: {},
        authenticated: false,
      }),
    ).rejects.toMatchObject({ code: 'INVALID_CREDENTIALS' })

    expect(expired).not.toHaveBeenCalled()
    unsubscribe()
  })
})

describe('error translation', () => {
  it('carries Retry-After off a rate limit', async () => {
    fetchMock.mockResolvedValue(
      respond(429, jsonError('RATE_LIMIT_EXCEEDED'), { 'Retry-After': '6' }),
    )

    const error = await request('/auth/login', {
      method: 'POST',
      body: {},
      authenticated: false,
    }).catch((e: ApiError) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).retryAfterSeconds).toBe(6)
  })

  it('keeps the server message on a validation failure', async () => {
    writeToken('t')
    fetchMock.mockResolvedValue(
      respond(400, jsonError('VALIDATION_ERROR', 'amount: must be positive')),
    )

    const error = await request('/transfers', {
      method: 'POST',
      body: {},
    }).catch((e: ApiError) => e)

    expect((error as ApiError).serverMessage).toBe('amount: must be positive')
  })

  it('reports a code it does not recognise as unexpected, not as itself', async () => {
    writeToken('t')
    fetchMock.mockResolvedValue(respond(418, jsonError('BRAND_NEW_CODE')))

    await expect(request('/accounts')).rejects.toMatchObject({
      code: 'UNEXPECTED_RESPONSE',
      status: 418,
    })
  })

  it('reports a non-JSON success as unexpected', async () => {
    // What a proxy pointing at the wrong port actually returns.
    writeToken('t')
    fetchMock.mockResolvedValue(respond(200, '<!DOCTYPE HTML><html>hi</html>'))

    await expect(request('/accounts')).rejects.toMatchObject({
      code: 'UNEXPECTED_RESPONSE',
    })
  })

  it('reports an unreachable server as a network error', async () => {
    writeToken('t')
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))

    await expect(request('/accounts')).rejects.toMatchObject({
      code: 'NETWORK_ERROR',
      status: 0,
    })
  })
})

describe('requests', () => {
  it('renders query parameters and omits the ones not set', async () => {
    writeToken('t')
    fetchMock.mockResolvedValue(respond(200, '{"content":[]}'))

    await request('/accounts/abc/transactions', {
      query: { page: 2, size: 20, from: undefined },
    })

    expect(fetchMock.mock.calls[0][0]).toBe(
      '/api/v1/accounts/abc/transactions?page=2&size=20',
    )
  })

  it('parses money out of a real response shape', async () => {
    writeToken('t')
    fetchMock.mockResolvedValue(
      respond(
        200,
        '[{"id":"a","accountNumber":"4471 0092","balance":1250.00,"status":"ACTIVE","createdAt":"2026-08-30T09:00:00Z"}]',
      ),
    )

    const accounts =
      await request<{ balance: string }[]>('/accounts')

    expect(accounts[0].balance).toBe('1250.00')
  })

  it('accepts a success with no body', async () => {
    writeToken('t')
    fetchMock.mockResolvedValue(respond(204, ''))

    await expect(request('/accounts')).resolves.toBeUndefined()
  })
})
