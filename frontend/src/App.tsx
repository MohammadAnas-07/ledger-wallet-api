import { useState } from 'react'

import { ApiError } from './api/errors'
import { AuthScreen } from './screens/auth/AuthScreen'
import type { LoginValues, RegisterValues } from './screens/auth/AuthScreen'

import './app-preview.css'

/*
 * Temporary — the chunk 1.3 host.
 *
 * The screen is finished; nothing is wired to the backend yet. So this file
 * stands in for the wiring, with handlers that wait and then produce a chosen
 * outcome. That is what makes the states reviewable: busy, disabled, a field
 * error, a rate limit, and the one that matters most — registration succeeding
 * and the sign-in behind it failing, which is otherwise very hard to provoke.
 *
 * The picker is dev scaffolding and looks like it. In chunk 1.4 this whole file
 * becomes the real thing: onLogin and onRegister call the API client, and the
 * picker goes away with the rest of it.
 */

type Outcome =
  | 'success'
  | 'invalid-credentials'
  | 'rate-limited'
  | 'network-error'
  | 'email-taken'
  | 'register-validation'
  | 'registered-then-login-failed'
  | 'lock-conflict'

const OUTCOMES: { value: Outcome; label: string }[] = [
  { value: 'success', label: 'Everything works' },
  { value: 'invalid-credentials', label: 'Login: wrong password (401)' },
  { value: 'rate-limited', label: 'Login: rate limited (429)' },
  { value: 'network-error', label: 'Login: server unreachable' },
  { value: 'email-taken', label: 'Register: email taken (409)' },
  { value: 'register-validation', label: 'Register: field errors (400)' },
  {
    value: 'registered-then-login-failed',
    label: 'Register works, sign-in fails',
  },
  { value: 'lock-conflict', label: 'Retryable conflict (409)' },
]

/** Long enough that the busy state is visible rather than a flicker. */
const LATENCY_MS = 700

function wait(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

export default function App() {
  const [outcome, setOutcome] = useState<Outcome>('success')
  const [signedInAs, setSignedInAs] = useState<string | null>(null)

  async function onLogin({ email }: LoginValues) {
    await wait(LATENCY_MS)

    switch (outcome) {
      case 'invalid-credentials':
        throw new ApiError({
          code: 'INVALID_CREDENTIALS',
          status: 401,
          serverMessage: 'Invalid email or password',
        })
      case 'rate-limited':
        throw new ApiError({
          code: 'RATE_LIMIT_EXCEEDED',
          status: 429,
          retryAfterSeconds: 6,
        })
      case 'network-error':
        throw new ApiError({ code: 'NETWORK_ERROR', status: 0 })
      case 'lock-conflict':
        throw new ApiError({ code: 'CONCURRENT_MODIFICATION', status: 409 })
      case 'registered-then-login-failed':
        // Only the sign-in half fails; the account was created a moment ago.
        throw new ApiError({
          code: 'RATE_LIMIT_EXCEEDED',
          status: 429,
          retryAfterSeconds: 4,
        })
      default:
        setSignedInAs(email)
    }
  }

  async function onRegister({ email }: RegisterValues) {
    await wait(LATENCY_MS)

    switch (outcome) {
      case 'email-taken':
        throw new ApiError({
          code: 'EMAIL_ALREADY_REGISTERED',
          status: 409,
          serverMessage: `Email already registered: ${email}`,
        })
      case 'register-validation':
        throw new ApiError({
          code: 'VALIDATION_ERROR',
          status: 400,
          serverMessage:
            'email: Email must be a valid address; password: Password must be between 12 and 72 characters',
        })
      default:
      // Registering succeeded. Whether the sign-in behind it does is onLogin's
      // business, which is exactly the split this chunk had to get right.
    }
  }

  return (
    <>
      <AuthScreen onLogin={onLogin} onRegister={onRegister} />

      <aside className="preview">
        <p className="preview__title">Chunk 1.3 preview — not shipped</p>
        <label className="preview__label" htmlFor="outcome">
          What the stubbed backend does
        </label>
        <select
          id="outcome"
          className="preview__select"
          value={outcome}
          onChange={(event) => setOutcome(event.target.value as Outcome)}
        >
          {OUTCOMES.map(({ value, label }) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        {signedInAs !== null && (
          <p className="preview__signed-in">
            Signed in as {signedInAs}. The dashboard arrives in Feature 2.
          </p>
        )}
      </aside>
    </>
  )
}
