import { BrowserRouter, Navigate, Route, Routes } from 'react-router'

import { AuthProvider } from './auth/AuthProvider'
import { useAuth } from './auth/useAuth'
import { AuthScreen } from './screens/auth/AuthScreen'
import { DashboardScreen } from './screens/dashboard/DashboardScreen'
import { HistoryScreen } from './screens/history/HistoryScreen'
import { TransferScreen } from './screens/transfer/TransferScreen'

/**
 * What the session allows, and where the address bar points.
 *
 * The protection is still a condition rather than a table of guarded routes,
 * and that is deliberate: being signed out is not a redirect here, it is a
 * different application. No route is reachable without a session because when
 * there is no session, none of them are rendered at all.
 *
 * Which also means a deep link survives signing in, for free. Open /transfer
 * with an expired token and you are asked to sign in *at* /transfer; when you
 * do, the transfer screen is what appears. No redirect dance, and no returnTo
 * parameter to carry around and get wrong.
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

  return (
    <Routes>
      <Route path="/" element={<DashboardScreen />} />
      <Route path="/transfer" element={<TransferScreen />} />
      <Route path="/history" element={<HistoryScreen />} />
      {/* Anything else is a typo or a stale link. The dashboard is the one
          screen that is always meaningful, so that is where they land. */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Session />
      </AuthProvider>
    </BrowserRouter>
  )
}
