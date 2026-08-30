import { useState } from 'react'
import type { FormEvent } from 'react'

import { deposit, newIdempotencyKey } from '../../api/endpoints'
import { ApiError, fieldErrors, userMessage } from '../../api/errors'
import type { Uuid } from '../../api/types'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { TextField } from '../../components/TextField'
import { formatAmount, isValidAmount } from '../../format/money'

import './deposit-action.css'

interface DepositActionProps {
  accountId: Uuid
  /**
   * Whether the amount field is showing. Controlled by the balance panel rather
   * than held here, because the panel needs to know: while money is being added
   * it hides the button that would navigate away mid-form.
   */
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Called after money has actually moved, so the screen can reload the
   *  balance and remount the list. */
  onDeposited: () => void
}

export function DepositAction({
  accountId,
  open,
  onOpenChange,
  onDeposited,
}: DepositActionProps) {
  const [amount, setAmount] = useState('')
  const [fieldError, setFieldError] = useState<string | undefined>(undefined)
  const [error, setError] = useState<ApiError | null>(null)
  const [added, setAdded] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  /*
   * The key for the deposit currently being attempted.
   *
   * It has to survive a retry and it has to not survive a change of mind. Sent
   * again with the same amount, the backend replays the original result instead
   * of moving the money twice — which is what makes the retry after a 409 safe.
   * Sent again with a *different* amount it is refused as
   * IDEMPOTENCY_KEY_REUSED, correctly: that is a different request wearing the
   * same name. So editing the amount discards the key, and the next attempt
   * mints a new one.
   */
  const [attemptKey, setAttemptKey] = useState<string | null>(null)

  function reset() {
    onOpenChange(false)
    setAmount('')
    setFieldError(undefined)
    setError(null)
    setAttemptKey(null)
  }

  function onAmountChange(next: string) {
    setAmount(next)
    setFieldError(undefined)
    // A new amount is a new intention, and must not reuse the previous key.
    setAttemptKey(null)
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    if (!isValidAmount(amount)) {
      setFieldError(
        amount.trim() === ''
          ? 'Enter an amount'
          : 'Enter an amount above zero, with at most two decimal places',
      )
      return
    }

    const key = attemptKey ?? newIdempotencyKey()
    setAttemptKey(key)

    setBusy(true)
    setError(null)
    try {
      const result = await deposit(accountId, {
        amount: amount.trim(),
        idempotencyKey: key,
      })
      setAdded(formatAmount(result.amount))
      reset()
      onDeposited()
    } catch (cause) {
      const failure =
        cause instanceof ApiError
          ? cause
          : new ApiError({ code: 'UNEXPECTED_RESPONSE', status: 0 })

      // A validation failure names the field; anything else is about the
      // request as a whole.
      const perField = fieldErrors(failure)
      if (perField.amount !== undefined) {
        setFieldError(perField.amount)
        setError(null)
      } else {
        setError(failure)
      }
    } finally {
      setBusy(false)
    }
  }

  if (!open) {
    return (
      <div className="deposit">
        {added !== null && (
          // Green, because money arriving is the one thing --credit marks.
          <Notice tone="success">{added} added to this wallet.</Notice>
        )}
        <Button
          variant="secondary"
          onClick={() => {
            setAdded(null)
            onOpenChange(true)
          }}
        >
          Add money
        </Button>
      </div>
    )
  }

  return (
    <form className="deposit" onSubmit={submit} noValidate>
      {error !== null && (
        /*
         * A lock conflict is not a failure and is not phrased as one. Nothing
         * was written, the amount is still in the field, and the key is still
         * the same — so pressing Add again is a genuine retry rather than a
         * second deposit.
         */
        <Notice tone={error.isRetryable ? 'retry' : 'error'}>
          {userMessage(error)}
        </Notice>
      )}

      <fieldset className="deposit__fields" disabled={busy}>
        <TextField
          label="Amount"
          name="amount"
          inputMode="decimal"
          autoComplete="off"
          placeholder="0.00"
          value={amount}
          error={fieldError}
          autoFocus
          onChange={(event) => onAmountChange(event.target.value)}
        />
      </fieldset>

      <div className="deposit__actions">
        <Button type="submit" busy={busy} busyLabel="Adding…">
          Add
        </Button>
        <Button
          type="button"
          variant="secondary"
          disabled={busy}
          onClick={reset}
        >
          Cancel
        </Button>
      </div>
    </form>
  )
}
