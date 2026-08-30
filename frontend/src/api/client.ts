/*
 * The single way this app talks to the backend.
 *
 * Everything goes through request(): the bearer header, the error translation,
 * and the decision about what a 401 means all live here, once. A component
 * calling fetch directly would be a component that gets one of those wrong.
 */

import { ApiError, isErrorResponse, toApiErrorCode } from './errors'
import { parseJson } from './json'
import { expireSession, readToken } from './session'

/*
 * Relative, not absolute. The dev server proxies /api to the backend, so the
 * browser only ever makes same-origin requests — which is what makes the
 * missing CORS configuration a non-issue in development. A build served from
 * the same origin as the API needs no change here either.
 */
const BASE_URL = '/api/v1'

export interface RequestOptions {
  method?: 'GET' | 'POST'
  body?: unknown
  query?: Record<string, string | number | undefined>
  /**
   * Send the bearer token. Default true — nearly every endpoint requires it,
   * and the filter chain denies by default, so this side does too.
   *
   * The two public endpoints pass false. That is not a formality: it is what
   * keeps a failed login from being mistaken for an expired session.
   */
  authenticated?: boolean
}

export async function request<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const { method = 'GET', body, query, authenticated = true } = options

  const headers: Record<string, string> = { Accept: 'application/json' }

  if (authenticated) {
    const token = readToken()
    if (token === null) {
      // No round trip: without a token the answer is already known, and asking
      // anyway would produce a 401 that looks like an expiry rather than a
      // caller that should have checked.
      throw new ApiError({ code: 'UNAUTHENTICATED', status: 401 })
    }
    headers.Authorization = `Bearer ${token}`
  }

  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  let response: Response
  try {
    response = await fetch(BASE_URL + path + queryString(query), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    // fetch only rejects when the request never completed: the server is down,
    // the proxy has nothing to talk to, the connection dropped.
    throw new ApiError({ code: 'NETWORK_ERROR', status: 0 })
  }

  const text = await response.text()

  if (!response.ok) {
    throw toApiError(response, text, authenticated)
  }

  // 201 with a body is the norm here, but an endpoint answering with none is
  // not an error — it is a T of void.
  if (text.length === 0) {
    return undefined as T
  }

  try {
    return parseJson<T>(text)
  } catch {
    // A 200 that is not JSON did not come from the backend. The usual cause is
    // the proxy pointing at something else entirely.
    throw new ApiError({ code: 'UNEXPECTED_RESPONSE', status: response.status })
  }
}

function queryString(query: RequestOptions['query']): string {
  if (!query) {
    return ''
  }
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) {
      params.set(key, String(value))
    }
  }
  const rendered = params.toString()
  return rendered.length === 0 ? '' : `?${rendered}`
}

function toApiError(
  response: Response,
  text: string,
  authenticated: boolean,
): ApiError {
  const body = readErrorBody(text)
  const serverMessage = body?.message ?? null
  const code = toApiErrorCode(body?.code)

  if (response.status === 401) {
    /*
     * Two different things share this status, and treating them alike would be
     * a real bug in both directions.
     *
     * A bare 401 from the filter chain — no body at all — means the token is
     * missing, malformed, or expired. The session is over: clear it and tell
     * the app, which sends the user back to sign in without an error message,
     * because expiry after fifteen minutes is not something they did wrong.
     *
     * A 401 carrying INVALID_CREDENTIALS came from the login endpoint and means
     * the password was wrong. Ending a session here would be nonsense — there
     * is no session — and the form needs to say so and stay put.
     */
    if (code === 'INVALID_CREDENTIALS') {
      return new ApiError({ code, status: 401, serverMessage })
    }
    if (authenticated) {
      expireSession()
    }
    return new ApiError({ code: 'UNAUTHENTICATED', status: 401, serverMessage })
  }

  if (code === null) {
    // A status with no code this app recognises: an unknown error shape, or a
    // response from something that is not the backend.
    return new ApiError({
      code: 'UNEXPECTED_RESPONSE',
      status: response.status,
      serverMessage,
    })
  }

  return new ApiError({
    code,
    status: response.status,
    serverMessage,
    retryAfterSeconds:
      code === 'RATE_LIMIT_EXCEEDED'
        ? retryAfterSeconds(response.headers.get('Retry-After'))
        : null,
  })
}

function readErrorBody(text: string) {
  if (text.length === 0) {
    return null
  }
  try {
    const body: unknown = JSON.parse(text)
    return isErrorResponse(body) ? body : null
  } catch {
    return null
  }
}

/** The backend sends whole seconds and never zero. Anything else — a missing
 *  header, an HTTP-date from something in between — is treated as unknown
 *  rather than guessed at. */
function retryAfterSeconds(header: string | null): number | null {
  if (header === null) {
    return null
  }
  const seconds = Number(header)
  return Number.isInteger(seconds) && seconds >= 0 ? seconds : null
}
