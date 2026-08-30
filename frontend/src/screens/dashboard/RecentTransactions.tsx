import { useCallback } from 'react'

import { getStatement } from '../../api/endpoints'
import { userMessage } from '../../api/errors'
import type { StatementEntryResponse, Uuid } from '../../api/types'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { useResource } from '../../data/useResource'
import { formatTimestamp } from '../../format/datetime'
import { formatSignedAmount } from '../../format/money'

import './recent-transactions.css'

/**
 * How many rows the dashboard asks for.
 *
 * Five, because this list answers "what just moved" and not "what have I ever
 * done" — a question the history screen exists for. Five also fits under the
 * balance without scrolling, which is what makes both readable at a glance.
 */
const RECENT = 5

export function RecentTransactions({ accountId }: { accountId: Uuid }) {
  /*
   * Mounted with a key of the account id by the dashboard, so switching wallets
   * gives this a fresh start rather than a refresh. That is deliberate: keeping
   * the previous wallet's rows on screen while the next one loads would put one
   * account's transactions directly under another account's balance, which is
   * the single most misleading thing this screen could do.
   */
  const statement = useResource(
    useCallback(() => getStatement(accountId, { page: 0, size: RECENT }), [accountId]),
    [accountId],
  )

  return (
    <section className="recent">
      <h2 className="caption recent__heading">Recent transactions</h2>

      {statement.loading && <PlaceholderRows />}

      {!statement.loading && statement.error !== null && (
        <Notice tone={statement.error.isRetryable ? 'retry' : 'error'}>
          {userMessage(statement.error)}
          <span className="recent__retry">
            <Button variant="secondary" onClick={statement.reload}>
              Try again
            </Button>
          </span>
        </Notice>
      )}

      {statement.data !== null && statement.data.content.length === 0 && (
        <p className="recent__empty body">Nothing has moved in this wallet yet.</p>
      )}

      {statement.data !== null && statement.data.content.length > 0 && (
        <ul className="recent__list">
          {statement.data.content.map((entry) => (
            <li className="recent__row" key={entry.entryId}>
              <Row entry={entry} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function Row({ entry }: { entry: StatementEntryResponse }) {
  const credit = entry.direction === 'CREDIT'

  return (
    <>
      <span className="recent__what">
        <span className="body">{describe(entry)}</span>
        <span className="caption">{formatTimestamp(entry.createdAt)}</span>
      </span>
      {/*
        * Sign and colour both come from direction. A credit is green because
        * money arriving is the one thing worth marking; a debit is neutral ink,
        * never red, because ordinary spending is not a failure — design.md §2.
        */}
      <span className={`amount ${credit ? 'amount--credit' : 'amount--debit'}`}>
        {formatSignedAmount(entry.amount, entry.direction)}
      </span>
    </>
  )
}

/**
 * What the row says happened.
 *
 * A transfer names the other account, and which side it was on: "To ACC-…"
 * reads as a thing that happened, where a bare account number reads as a label
 * and leaves the direction to be inferred from the sign alone.
 *
 * A deposit or withdrawal has no counterparty to name — the other side is the
 * system account, and the API withholds it deliberately, because it is internal
 * plumbing rather than someone the user dealt with.
 */
function describe(entry: StatementEntryResponse): string {
  if (entry.counterparty !== null) {
    return entry.direction === 'CREDIT'
      ? `From ${entry.counterparty.accountNumber}`
      : `To ${entry.counterparty.accountNumber}`
  }
  return entry.type === 'DEPOSIT' ? 'Deposit' : 'Withdrawal'
}

/** Held still, like the balance placeholder — the shape of what is coming
 *  rather than a spinner asking the reader to wait. */
function PlaceholderRows() {
  return (
    <ul className="recent__list" aria-busy="true">
      {[0, 1, 2].map((row) => (
        <li className="recent__row" key={row}>
          <span className="recent__what">
            <span className="body recent__placeholder">—</span>
          </span>
        </li>
      ))}
    </ul>
  )
}
