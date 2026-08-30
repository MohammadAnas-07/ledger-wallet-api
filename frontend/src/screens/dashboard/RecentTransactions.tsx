import { useCallback } from 'react'
import { Link } from 'react-router'

import { getStatement } from '../../api/endpoints'
import { userMessage } from '../../api/errors'
import type { Uuid } from '../../api/types'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import {
  TransactionList,
  TransactionListPlaceholder,
} from '../../components/TransactionList'
import { useResource } from '../../data/useResource'

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

  const rows = statement.data?.content ?? []
  const hasMore = (statement.data?.totalElements ?? 0) > rows.length

  return (
    <section className="recent">
      <div className="recent__heading">
        <h2 className="caption">Recent transactions</h2>
        {/* Only once there is more than this list shows. Offering "see all" for
            a list that is already all of it is a link to the same thing. */}
        {hasMore && (
          <Link className="recent__all" to={`/history?account=${accountId}`}>
            See all
          </Link>
        )}
      </div>

      {statement.loading && <TransactionListPlaceholder />}

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

      {statement.data !== null && rows.length === 0 && (
        <p className="recent__empty body">Nothing has moved in this wallet yet.</p>
      )}

      {rows.length > 0 && <TransactionList entries={rows} />}
    </section>
  )
}
