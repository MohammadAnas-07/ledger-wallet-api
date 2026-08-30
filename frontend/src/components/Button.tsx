import type { ButtonHTMLAttributes, ReactNode } from 'react'

import './button.css'

type Variant = 'primary' | 'secondary'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  /**
   * The action is in flight. The button disables itself and says so, which is
   * the frontend half of double-submit protection — the backend's half is the
   * idempotency key. Both halves exist because either alone can be defeated.
   */
  busy?: boolean
  /** What to say while busy. "Log in" becomes "Signing in…", not "Log in…". */
  busyLabel?: string
  children: ReactNode
}

/**
 * The pill CTA from design.md §5.
 *
 * One primary per screen. Secondary is the same geometry with no fill — if two
 * things look equally primary, neither is.
 */
export function Button({
  variant = 'primary',
  busy = false,
  busyLabel,
  disabled,
  children,
  ...rest
}: ButtonProps) {
  return (
    <button
      {...rest}
      className={`button button--${variant}`}
      disabled={disabled || busy}
      // Screen readers are told the control is working rather than broken:
      // without this, a disabled button that changed its label is just a
      // disabled button.
      aria-busy={busy || undefined}
    >
      {busy && busyLabel ? busyLabel : children}
    </button>
  )
}
