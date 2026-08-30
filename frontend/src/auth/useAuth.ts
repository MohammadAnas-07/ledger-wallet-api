import { useContext } from 'react'

import { AuthContext } from './context'
import type { AuthState } from './context'

/**
 * The session, for any component that needs it.
 *
 * Throws outside the provider rather than returning a signed-out default: a
 * default would turn a wiring mistake into a screen that silently never signs
 * anyone in, which is the kind of bug that survives a demo.
 */
export function useAuth(): AuthState {
  const value = useContext(AuthContext)
  if (value === null) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return value
}
