import { createContext } from 'react'

import type { UserResponse } from '../api/types'
import type { LoginValues, RegisterValues } from '../screens/auth/AuthScreen'

/**
 * Three states, not a boolean.
 *
 * `checking` exists because a token in localStorage is not the same thing as a
 * session: it may have expired while the tab was closed. Treating "we have a
 * token" as "we are signed in" would render the dashboard and then yank it
 * away a moment later when the first request comes back 401.
 */
export type AuthStatus = 'checking' | 'signed-out' | 'signed-in'

export interface AuthState {
  status: AuthStatus
  /** Set exactly when status is `signed-in`. */
  user: UserResponse | null
  /** Signs in and loads the profile. Rejects with an ApiError. */
  signIn: (values: LoginValues) => Promise<void>
  /** Creates the account. Signs nobody in — the backend issues no token here. */
  register: (values: RegisterValues) => Promise<void>
  signOut: () => void
}

// No default: a component reading this outside the provider is a wiring
// mistake, and a default object would hide it behind a screen that quietly
// never signs in. useAuth throws instead.
export const AuthContext = createContext<AuthState | null>(null)
