import { useCallback, useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router'

import { getStatement, listAccounts } from '../../api/endpoints'
import { userMessage } from '../../api/errors'
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

export function TransferScreen() {
  const accounts = useResource(
    useCallback(() => listAccounts(), []),
    [],
  )

  const [fromId, setFromId] = useState<Uuid | ''>('')
  const [toId, setToId] = useState<Uuid | typeof ELSEWHERE | ''>('')
  const [elsewhereId, setElsewhereId] = useState('')
  const [amount, setAmount] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})

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

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFields(validate())
    // Wiring is chunk 3.3. Everything above this line is finished.
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
                onChange={(event) => setToId(event.target.value)}
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
                  onChange={(event) => setElsewhereId(event.target.value)}
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
                onChange={(event) => setAmount(event.target.value)}
              />

              <div className="transfer__actions">
                <Button type="submit">Send</Button>
                <Link className="transfer__back" to="/">
                  Cancel
                </Link>
              </div>

              <p className="caption transfer__pending">
                Sending is wired up in the next chunk.
              </p>
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
