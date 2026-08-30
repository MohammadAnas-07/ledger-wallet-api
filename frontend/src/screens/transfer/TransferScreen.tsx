import { useCallback, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'

import {
  getStatement,
  listAccounts,
  newIdempotencyKey,
  transfer,
} from '../../api/endpoints'
import { ApiError, fieldErrors, userMessage } from '../../api/errors'
import type { AccountResponse, Uuid } from '../../api/types'
import { AppHeader } from '../../components/AppHeader'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { useResource } from '../../data/useResource'
import { formatAmount, isValidAmount } from '../../format/money'

import { counterpartiesFrom } from './counterparties'
import './transfer-screen.css'

/**
 * How much statement to read when looking for people to send to.
 *
 * Fifty is one request — the backend clamps a page at a hundred — and covers
 * far more history than anyone scrolls looking for a familiar account number.
 */
const HISTORY_DEPTH = 50

/** The value of the option that reveals the paste-an-id field. */
const ELSEWHERE = 'elsewhere'

const UUID_SHAPE =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * What the backend calls a field, and what this form calls it.
 *
 * A validation failure names the request body's field; a person needs the label
 * they filled in. Anything not on this list falls back to a notice rather than
 * being attached to the wrong input.
 */
const FIELD_NAMES: Record<string, string> = {
  fromAccountId: 'from',
  toAccountId: 'to',
  amount: 'amount',
}

export function TransferScreen() {
  const navigate = useNavigate()

  const accounts = useResource(
    useCallback(() => listAccounts(), []),
    [],
  )

  const [fromId, setFromId] = useState<Uuid | ''>('')
  const [toId, setToId] = useState<Uuid | typeof ELSEWHERE | ''>('')
  const [elsewhereId, setElsewhereId] = useState('')
  const [amount, setAmount] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState<ApiError | null>(null)
  const [busy, setBusy] = useState(false)

  /** The synchronous half of the in-flight guard — see submit(). */
  const inFlight = useRef(false)

  /*
   * The key for the transfer currently being attempted.
   *
   * Kept across a retry so that repeating a request the server may already have
   * committed replays the original result instead of moving the money twice —
   * the whole point of retrying a 409 at all. Discarded the moment any input
   * changes, because the same key with different contents is a different
   * request wearing the same name, and the backend refuses that on purpose.
   */
  const [attemptKey, setAttemptKey] = useState<string | null>(null)

  /** Any edit invalidates the attempt it belonged to. */
  function edited() {
    setAttemptKey(null)
    setError(null)
  }

  const wallets = accounts.data ?? []
  const source = wallets.find((wallet) => wallet.id === fromId) ?? null

  /*
   * Who this wallet has sent to or received from before.
   *
   * Read from the source wallet's own statement, which is the only place the
   * app is ever told another account's id — the API takes a UUID and offers no
   * way to look one up from an account number. Reloaded when the source
   * changes, because "who have I dealt with" is a question about one wallet.
   */
  const history = useResource(
    useCallback(
      () =>
        source === null
          ? Promise.resolve(null)
          : getStatement(source.id, { page: 0, size: HISTORY_DEPTH }),
      [source],
    ),
    [source?.id],
  )

  const known = counterpartiesFrom(history.data?.content ?? [], wallets)
  const otherWallets = wallets.filter((wallet) => wallet.id !== fromId)

  function validate(): Record<string, string> {
    const found: Record<string, string> = {}

    if (fromId === '') {
      found.from = 'Choose a wallet to send from'
    }

    if (toId === '') {
      found.to = 'Choose where to send it'
    } else if (toId === ELSEWHERE) {
      if (elsewhereId.trim() === '') {
        found.elsewhere = 'Enter an account id'
      } else if (!UUID_SHAPE.test(elsewhereId.trim())) {
        found.elsewhere = 'That is not an account id'
      } else if (elsewhereId.trim() === fromId) {
        // The dropdown cannot offer the source, but a pasted id can still name
        // it. The backend refuses this too; saying so here saves the round trip.
        found.elsewhere = 'That is the wallet you are sending from'
      }
    }

    if (!isValidAmount(amount)) {
      found.amount =
        amount.trim() === ''
          ? 'Enter an amount'
          : 'Enter an amount above zero, with at most two decimal places'
    }

    /*
     * The balance is deliberately not checked here.
     *
     * The figure on screen was true when it was fetched and may not be now, and
     * the ledger is the only thing that can say whether a transfer fits. Letting
     * the server answer means the user is told the truth rather than a guess —
     * and 422 INSUFFICIENT_FUNDS has a designed treatment waiting for it in the
     * next chunk.
     */

    return found
  }

  /** Where the destination came from, so a failure about it can point at the
   *  control the user actually used. */
  const destinationField = toId === ELSEWHERE ? 'elsewhere' : 'to'

  function report(cause: unknown) {
    const failure =
      cause instanceof ApiError
        ? cause
        : new ApiError({ code: 'UNEXPECTED_RESPONSE', status: 0 })

    if (failure.code === 'VALIDATION_ERROR') {
      const named: Record<string, string> = {}
      let unfielded: string | undefined
      for (const [field, message] of Object.entries(fieldErrors(failure))) {
        const local = FIELD_NAMES[field]
        if (local === undefined) {
          unfielded = message
        } else {
          named[local === 'to' ? destinationField : local] = message
        }
      }
      setFields(named)
      setError(unfielded === undefined ? null : failure)
      return
    }

    if (failure.code === 'SELF_TRANSFER_NOT_ALLOWED') {
      // Only reachable through the pasted-id field — the dropdown never offers
      // the source. Pointing at that field beats a banner about the form.
      setFields({ [destinationField]: 'That is the wallet you are sending from' })
      setError(null)
      return
    }

    if (failure.code === 'ACCOUNT_NOT_FOUND') {
      /*
       * A well-formed id for an account that does not exist, which is exactly
       * what a mistyped paste produces. The shape check cannot catch it — only
       * the server knows which ids are real — so the answer belongs on the same
       * field rather than in a notice about the transfer.
       */
      setFields({ [destinationField]: 'No account has that id' })
      setError(null)
      return
    }

    if (failure.code === 'IDEMPOTENCY_KEY_REUSED') {
      // The key no longer matches what is on screen. Drop it so the next press
      // is a fresh request rather than the same refusal again.
      setAttemptKey(null)
    }

    setFields({})
    setError(failure)
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()

    /*
     * The only guard that holds inside a single tick.
     *
     * `busy` disables the button and `attemptKey` keeps a retry idempotent, and
     * both are React state — which means both are read from a closure that a
     * burst of submits in one tick all share. Four submits before the first
     * re-render all see busy false and attemptKey null, so all four mint their
     * own key, and four distinct transfers are exactly what the backend is then
     * asked for. That is not hypothetical: holding Enter on a focused form
     * repeats the submit event, and it moved money four times here before this
     * line existed.
     *
     * A ref is written synchronously, so the second submit in the burst sees it.
     */
    if (inFlight.current) {
      return
    }

    const found = validate()
    setFields(found)
    if (Object.keys(found).length > 0) {
      setError(null)
      return
    }

    const key = attemptKey ?? newIdempotencyKey()
    setAttemptKey(key)

    inFlight.current = true
    setBusy(true)
    setError(null)
    try {
      await transfer({
        fromAccountId: fromId,
        toAccountId: toId === ELSEWHERE ? elsewhereId.trim() : toId,
        amount: amount.trim(),
        idempotencyKey: key,
      })
      /*
       * Straight back to the wallet, with no message.
       *
       * The balance is the confirmation — it is lower, and the transfer is at
       * the top of the list. A banner saying it worked, above a figure that
       * says the same thing, is one of them being redundant.
       */
      navigate('/')
    } catch (cause) {
      report(cause)
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  const ready = accounts.data !== null && wallets.length > 0

  return (
    <div className="transfer">
      <div className="transfer__column">
        <AppHeader />
        <main className="transfer__main">
          <h1 className="title">Send money</h1>

          {accounts.loading && (
            <p className="transfer__note body">Loading your wallets…</p>
          )}

          {accounts.error !== null && accounts.data === null && (
            <Notice tone={accounts.error.isRetryable ? 'retry' : 'error'}>
              {userMessage(accounts.error)}
              <span className="transfer__retry">
                <Button variant="secondary" onClick={accounts.reload}>
                  Try again
                </Button>
              </span>
            </Notice>
          )}

          {accounts.data !== null && wallets.length === 0 && (
            <section className="transfer__panel">
              <p className="body">You need a wallet before you can send money.</p>
              <Link className="button button--primary" to="/">
                Go to your wallet
              </Link>
            </section>
          )}

          {ready && (
            <form className="transfer__panel" onSubmit={submit} noValidate>
              {error !== null && (
                /*
                 * A lock conflict is not a failure and is not dressed as one:
                 * nothing was written, everything typed is still here, and the
                 * key is unchanged — so pressing Send again is a retry of the
                 * same transfer rather than a second one.
                 */
                <Notice tone={error.isRetryable ? 'retry' : 'error'}>
                  {userMessage(error)}
                </Notice>
              )}

              {/* Disabled as a set while the request is in flight: one
                  attribute cannot fall out of step with the others, and this is
                  the frontend half of double-submit protection. The backend's
                  half is the key above. */}
              <fieldset className="transfer__fields" disabled={busy}>
              <SelectField
                label="From"
                value={fromId}
                error={fields.from}
                onChange={(event) => {
                  setFromId(event.target.value)
                  // The destination list belongs to the source. Keeping a
                  // choice made against a different wallet's history would
                  // silently offer an account this one has never dealt with.
                  setToId('')
                  setFields({})
                  edited()
                }}
              >
                <option value="">Choose a wallet</option>
                {wallets.map((wallet) => (
                  <option key={wallet.id} value={wallet.id}>
                    {describeWallet(wallet)}
                  </option>
                ))}
              </SelectField>

              <SelectField
                label="To"
                value={toId}
                error={fields.to}
                disabled={fromId === ''}
                hint={
                  fromId === ''
                    ? 'Choose a wallet to send from first.'
                    : undefined
                }
                onChange={(event) => {
                  setToId(event.target.value)
                  edited()
                }}
              >
                <option value="">Choose an account</option>

                {otherWallets.length > 0 && (
                  <optgroup label="Your wallets">
                    {otherWallets.map((wallet) => (
                      <option key={wallet.id} value={wallet.id}>
                        {describeWallet(wallet)}
                      </option>
                    ))}
                  </optgroup>
                )}

                {known.length > 0 && (
                  <optgroup label="You have sent to or received from">
                    {known.map((other) => (
                      <option key={other.accountId} value={other.accountId}>
                        {other.accountNumber}
                      </option>
                    ))}
                  </optgroup>
                )}

                {/*
                  * The escape hatch, and the reason it has to exist: nothing
                  * above it can name someone this wallet has never dealt with,
                  * so without this a first transfer to a new person is
                  * impossible from the UI.
                  */}
                <option value={ELSEWHERE}>Another account…</option>
              </SelectField>

              {toId === ELSEWHERE && (
                <TextField
                  label="Account id"
                  value={elsewhereId}
                  error={fields.elsewhere}
                  hint="Ask them for it — the API identifies accounts by id, not by account number."
                  placeholder="00000000-0000-0000-0000-000000000000"
                  autoComplete="off"
                  spellCheck={false}
                  onChange={(event) => {
                    setElsewhereId(event.target.value)
                    edited()
                  }}
                />
              )}

              {history.error !== null && (
                <Notice tone="retry">
                  Could not read this wallet&apos;s history, so accounts you have
                  dealt with are not listed. You can still paste an account id.
                </Notice>
              )}

              <TextField
                label="Amount"
                inputMode="decimal"
                autoComplete="off"
                placeholder="0.00"
                value={amount}
                error={fields.amount}
                onChange={(event) => {
                  setAmount(event.target.value)
                  edited()
                }}
              />
              </fieldset>

              <div className="transfer__actions">
                <Button type="submit" busy={busy} busyLabel="Sending…">
                  Send
                </Button>
                {!busy && (
                  <Link className="transfer__back" to="/">
                    Cancel
                  </Link>
                )}
              </div>
            </form>
          )}
        </main>
      </div>
    </div>
  )
}

/** Account number and what is in it — the balance is what tells someone which
 *  wallet they meant. */
function describeWallet(wallet: AccountResponse): string {
  return `${wallet.accountNumber} · ${formatAmount(wallet.balance)}`
}
