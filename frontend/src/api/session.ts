/*
 * Where the access token lives, and who finds out when it stops working.
 *
 * Storage is localStorage: the token survives a refresh, which is the only
 * reason it is stored at all. It is a fifteen-minute bearer token with no
 * refresh endpoint and no revocation, so expiry is the normal end of a session
 * rather than an exception — see phases.md, Stage 2 decisions.
 *
 * This module is the storage and the notification, and nothing more. React
 * state is built on top of it in chunk 1.4; the API client needs the token
 * before any of that exists, which is why the two are separate.
 */

const STORAGE_KEY = 'wallet.accessToken'

type Listener = () => void

const listeners = new Set<Listener>()

/**
 * localStorage is not always available — a private window, a browser set to
 * block site data, and the access itself throws rather than returning null. A
 * wallet that cannot remember a token across a refresh is worse than one that
 * can; one that white-screens on load is worse than both.
 */
function safeRead(): string | null {
  try {
    return window.localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

let inMemoryToken: string | null = safeRead()

export function readToken(): string | null {
  return inMemoryToken
}

export function writeToken(token: string): void {
  inMemoryToken = token
  try {
    window.localStorage.setItem(STORAGE_KEY, token)
  } catch {
    // Kept in memory only. The session works until the tab is closed.
  }
}

export function clearToken(): void {
  inMemoryToken = null
  try {
    window.localStorage.removeItem(STORAGE_KEY)
  } catch {
    // Already gone as far as this tab is concerned.
  }
}

/**
 * Called when an authenticated request comes back 401 — the token expired, or
 * was never valid. The token is cleared before listeners run, so nothing can
 * observe a signed-out app that still holds a token.
 *
 * Note what this is not: a login failure. A 401 from the login endpoint means
 * the password was wrong, and the client never routes it here.
 */
export function onSessionExpired(listener: Listener): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

export function expireSession(): void {
  clearToken()
  for (const listener of listeners) {
    listener()
  }
}
