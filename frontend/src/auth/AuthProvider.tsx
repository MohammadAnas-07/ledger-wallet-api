import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'

import { login, me, register as registerAccount } from '../api/endpoints'
import { clearToken, onSessionExpired, readToken, writeToken } from '../api/session'
import type { LoginRequest, RegisterRequest, UserResponse } from '../api/types'

import { AuthContext } from './context'
import type { AuthState, AuthStatus } from './context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null)
  // Start at `checking` only when there is something to check. With no token
  // there is nothing to wait for, and a spinner before the sign-in form would
  // be a delay with no question behind it.
  const [status, setStatus] = useState<AuthStatus>(() =>
    readToken() === null ? 'signed-out' : 'checking',
  )

  /*
   * The session ending is not always something this app did.
   *
   * A token expires after fifteen minutes and there is no refresh endpoint, so
   * the usual way a session ends is a request coming back 401 — from anywhere,
   * at any time. The client clears the token and announces it; this is the one
   * place that turns that into the user being signed out. Every screen gets the
   * behaviour without knowing about it.
   */
  useEffect(() => onSessionExpired(() => {
    setUser(null)
    setStatus('signed-out')
  }), [])

  /*
   * A token found in storage is a claim, not a session. Verifying it on start
   * costs one request and is the difference between showing the dashboard and
   * showing the dashboard correctly.
   *
   * No error handling here on purpose: a 401 is already handled by the
   * subscription above, and anything else — the server being down — is handled
   * by the same route, because an unverifiable token is not a session either.
   */
  useEffect(() => {
    if (status !== 'checking') {
      return
    }

    let cancelled = false

    me()
      .then((profile) => {
        if (!cancelled) {
          setUser(profile)
          setStatus('signed-in')
        }
      })
      .catch(() => {
        if (!cancelled) {
          clearToken()
          setUser(null)
          setStatus('signed-out')
        }
      })

    // React 19 runs effects twice in development. Without this, the slower of
    // two identical /me responses could arrive after a sign-out and put the
    // user back.
    return () => {
      cancelled = true
    }
  }, [status])

  const signIn = useCallback(async (values: LoginRequest) => {
    const auth = await login(values)
    writeToken(auth.accessToken)

    try {
      setUser(await me())
      setStatus('signed-in')
    } catch (error) {
      /*
       * The token is good — it was just issued — but the profile did not load.
       * Keeping it would leave the app holding a token with no user behind it,
       * which renders as signed-out anyway and then fails confusingly on the
       * next screen. Dropping it makes the failure honest and repeatable.
       */
      clearToken()
      setUser(null)
      setStatus('signed-out')
      throw error
    }
  }, [])

  const register = useCallback(async (values: RegisterRequest) => {
    // Returns the created user and no token. Signing in is a separate call, and
    // AuthScreen makes it — so that a registration that works followed by a
    // sign-in that does not can be told apart from a registration that failed.
    await registerAccount(values)
  }, [])

  const signOut = useCallback(() => {
    clearToken()
    setUser(null)
    setStatus('signed-out')
  }, [])

  const value = useMemo<AuthState>(
    () => ({ status, user, signIn, register, signOut }),
    [status, user, signIn, register, signOut],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}
