import { useState } from 'react'
import type { FormEvent } from 'react'

import { ApiError, fieldErrors, userMessage } from '../../api/errors'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import type { NoticeTone } from '../../components/Notice'
import { TextField } from '../../components/TextField'

import './auth-screen.css'

export type AuthMode = 'login' | 'register'

export interface LoginValues {
  email: string
  password: string
}

export interface RegisterValues extends LoginValues {
  fullName: string
}

export interface AuthScreenProps {
  /** Resolves when the caller is signed in. Rejects with an ApiError. */
  onLogin: (values: LoginValues) => Promise<void>
  /**
   * Resolves when the account exists. Does **not** sign anyone in — the
   * backend's register endpoint answers with a user and no token.
   */
  onRegister: (values: RegisterValues) => Promise<void>
}

interface Message {
  tone: NoticeTone
  text: string
  /** A secondary line under the main one — the reason, when the reason is
   *  worth knowing but is not the headline. */
  detail?: string
}

const NO_FIELD_ERRORS: Record<string, string> = {}

export function AuthScreen({ onLogin, onRegister }: AuthScreenProps) {
  const [mode, setMode] = useState<AuthMode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fullName, setFullName] = useState('')

  const [fields, setFields] = useState<Record<string, string>>(NO_FIELD_ERRORS)
  const [message, setMessage] = useState<Message | null>(null)
  const [busy, setBusy] = useState(false)

  const registering = mode === 'register'

  function switchMode(next: AuthMode) {
    setMode(next)
    // Errors belong to the form that produced them. Carrying "that email is
    // already registered" across to the login form would be answering a
    // question the user has stopped asking.
    setFields(NO_FIELD_ERRORS)
    setMessage(null)
    setPassword('')
  }

  /**
   * Local checks, run before any request.
   *
   * These mirror the backend's constraints; they do not replace them. The point
   * is to answer while the user is still in the field, instead of spending a
   * round trip to be told a password is too short.
   *
   * Login checks presence only, deliberately. The backend is looser there for a
   * reason: applying the registration rules at login would reject an existing
   * user whose password predates a rule change, and would advertise the current
   * rules to anyone guessing.
   */
  function validate(): Record<string, string> {
    const found: Record<string, string> = {}

    if (email.trim() === '') {
      found.email = 'Email is required'
    } else if (!/^\S+@\S+\.\S+$/.test(email.trim())) {
      found.email = 'Email must be a valid address'
    }

    if (password === '') {
      found.password = 'Password is required'
    } else if (registering && (password.length < 12 || password.length > 72)) {
      found.password = 'Password must be between 12 and 72 characters'
    }

    if (registering && fullName.trim() === '') {
      found.fullName = 'Full name is required'
    }

    return found
  }

  function reportFailure(error: unknown, fallback: string) {
    if (!(error instanceof ApiError)) {
      setMessage({ tone: 'error', text: fallback })
      return
    }

    const perField = fieldErrors(error)
    // The empty key holds a validation message that named no field. It still
    // has to be shown somewhere rather than dropped.
    const unfielded = perField['']
    const named = { ...perField }
    delete named['']

    setFields(named)
    setMessage({
      // A conflict that can simply be repeated is not phrased as a failure.
      tone: error.isRetryable ? 'retry' : 'error',
      text: unfielded ?? userMessage(error),
    })
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    const found = validate()
    setFields(found)
    if (Object.keys(found).length > 0) {
      setMessage(null)
      return
    }

    setBusy(true)
    setMessage(null)

    try {
      if (!registering) {
        try {
          await onLogin({ email: email.trim(), password })
        } catch (error) {
          reportFailure(error, 'Could not sign you in. Try again.')
        }
        return
      }

      /*
       * Registering is two calls, because register issues no token. They fail
       * differently, and telling them apart is the whole reason this screen
       * orchestrates the pair rather than hiding both behind one prop.
       */
      try {
        await onRegister({
          email: email.trim(),
          password,
          fullName: fullName.trim(),
        })
      } catch (error) {
        reportFailure(error, 'Could not create your account. Try again.')
        return
      }

      try {
        await onLogin({ email: email.trim(), password })
      } catch (error) {
        /*
         * The account exists. Only the sign-in failed.
         *
         * Reporting this as a failed registration would be a lie that costs
         * real time: the user tries again, is told the email is already taken,
         * and has no way to know which of the two statements was true. So the
         * screen switches to sign-in, keeps the email, and says plainly that
         * the account is there and only this last step has to be repeated.
         */
        setMode('login')
        setPassword('')
        setFields(NO_FIELD_ERRORS)
        setMessage({
          tone: 'success',
          text:
            'Your account is created. Signing in did not go through — sign in below to continue.',
          // Why it failed is worth knowing, but it is secondary to the account
          // existing, and it is not a fault of any one field: a rate limit or
          // an unreachable server has nothing to do with the password. Marking
          // the password invalid for it would be pointing at the wrong thing.
          detail:
            error instanceof ApiError ? userMessage(error) : undefined,
        })
        return
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="auth">
      <div className="auth__panel">
        <header className="auth__header">
          <h1 className="title">
            {registering ? 'Create your wallet' : 'Sign in'}
          </h1>
          <p className="caption">
            {registering
              ? 'One account, one place to see what moved.'
              : 'Welcome back.'}
          </p>
        </header>

        {message !== null && (
          <Notice tone={message.tone}>
            {message.text}
            {message.detail !== undefined && (
              <span className="notice__detail caption">{message.detail}</span>
            )}
          </Notice>
        )}

        <form className="auth__form" onSubmit={handleSubmit} noValidate>
          {/* The set is disabled, not each input: one attribute cannot fall out
              of step with the others. */}
          <fieldset className="auth__fields" disabled={busy}>
            {registering && (
              <TextField
                label="Full name"
                name="fullName"
                autoComplete="name"
                value={fullName}
                error={fields.fullName}
                onChange={(event) => setFullName(event.target.value)}
              />
            )}

            <TextField
              label="Email"
              name="email"
              type="email"
              inputMode="email"
              autoComplete={registering ? 'email' : 'username'}
              value={email}
              error={fields.email}
              onChange={(event) => setEmail(event.target.value)}
            />

            <TextField
              label="Password"
              name="password"
              type="password"
              autoComplete={registering ? 'new-password' : 'current-password'}
              value={password}
              error={fields.password}
              hint={registering ? 'At least 12 characters.' : undefined}
              onChange={(event) => setPassword(event.target.value)}
            />
          </fieldset>

          <Button
            type="submit"
            busy={busy}
            busyLabel={registering ? 'Creating account…' : 'Signing in…'}
          >
            {registering ? 'Create account' : 'Log in'}
          </Button>
        </form>

        <p className="auth__switch caption">
          {registering ? 'Already have an account?' : 'No account yet?'}{' '}
          <button
            type="button"
            className="auth__link"
            disabled={busy}
            onClick={() => switchMode(registering ? 'login' : 'register')}
          >
            {registering ? 'Sign in' : 'Create one'}
          </button>
        </p>
      </div>
    </main>
  )
}
