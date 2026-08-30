import type { StatementEntryResponse } from '../api/types'
import { formatTimestamp } from '../format/datetime'
import { formatSignedAmount } from '../format/money'

import './transaction-list.css'

/*
 * One statement, rendered.
 *
 * Written for the dashboard first and extracted when the history screen became
 * its second reader — which is the point at which "the same list in two places"
 * stops being a guess. Two copies of a row that renders money is two places for
 * a credit to turn the wrong colour.
 */

export function TransactionList({
  entries,
}: {
  entries: StatementEntryResponse[]
}) {
  return (
    <ul className="txns">
      {entries.map((entry) => (
        <li className="txns__row" key={entry.entryId}>
          <Row entry={entry} />
        </li>
      ))}
    </ul>
  )
}

/** Held still, not spun. The shape of what is coming rather than a request to
 *  wait — same treatment as the balance placeholder. */
export function TransactionListPlaceholder({ rows = 3 }: { rows?: number }) {
  return (
    <ul className="txns" aria-busy="true">
      {Array.from({ length: rows }, (_, row) => (
        <li className="txns__row" key={row}>
          <span className="txns__what">
            <span className="body txns__placeholder">—</span>
          </span>
        </li>
      ))}
    </ul>
  )
}

function Row({ entry }: { entry: StatementEntryResponse }) {
  const credit = entry.direction === 'CREDIT'

  return (
    <>
      <span className="txns__what">
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
