import { AuthProvider } from './auth/AuthProvider'
import { useAuth } from './auth/useAuth'
import { AuthScreen } from './screens/auth/AuthScreen'
import { SignedInScreen } from './screens/signed-in/SignedInScreen'

/**
 * Which screen the session allows.
 *
 * This is the protected route, and it is a condition rather than a router
 * because there is exactly one authenticated screen so far. Routing between the
 * dashboard, the transfer form, and the history is a Feature 2 problem, and
 * picking a router now would be choosing it against three screens that do not
 * exist yet.
 *
 * What matters is the shape: an unauthenticated screen is not reachable from
 * here, and the signed-in one is not rendered until the session is confirmed —
 * not merely until a token is present.
 */
function Session() {
  const { status, signIn, register } = useAuth()

  if (status === 'checking') {
    // A stored token is being verified against /auth/me. Deliberately quiet:
    // this is normally one request on a warm connection, and a spinner that
    // appears for 80ms reads as a flicker, not as progress.
    return <main className="app-checking" aria-busy="true" />
  }

  if (status === 'signed-out') {
    return <AuthScreen onLogin={signIn} onRegister={register} />
  }

  return <SignedInScreen />
}

export default function App() {
  return (
    <AuthProvider>
      <Session />
    </AuthProvider>
  )
}
