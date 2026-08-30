import { useEffect, useState } from 'react'
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
    return <CheckingSession />
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

/**
 * The gap while a stored token is verified against /auth/me.
 *
 * Quiet at first, and deliberately so: this is normally one request on a warm
 * connection, and something that appears for 80ms reads as a flicker rather
 * than as progress.
 *
 * But "normally" was carrying the whole argument. With a backend still warming
 * up, that request took thirty seconds, and for all thirty the app was a blank
 * page — no text, no indication anything was happening, indistinguishable from
 * broken. So the quiet has a limit now: past a couple of seconds, long enough
 * that no healthy load reaches it, the screen says what it is waiting for.
 */
function CheckingSession() {
  const [slow, setSlow] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => setSlow(true), 2000)
    return () => clearTimeout(timer)
  }, [])

  return (
    <main className="app-checking" aria-busy="true">
      {slow && (
        <p className="app-checking__note body">Still checking your session…</p>
      )}
    </main>
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
