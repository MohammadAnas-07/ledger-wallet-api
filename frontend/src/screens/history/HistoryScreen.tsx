import { useCallback, useEffect } from 'react'
import { Link, useSearchParams } from 'react-router'

import { getStatement, listAccounts } from '../../api/endpoints'
import { userMessage } from '../../api/errors'
import { AppHeader } from '../../components/AppHeader'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import {
  TransactionList,
  TransactionListPlaceholder,
} from '../../components/TransactionList'
import { useResource } from '../../data/useResource'

import './history-screen.css'

/**
 * Rows per page.
 *
 * Twenty is the backend's own default, and about a screen of a statement — far
 * enough to scan a month, short enough that the page control stays reachable.
 * The server clamps a page at a hundred, so this is well inside what it allows.
 */
const PAGE_SIZE = 20

/**
 * One wallet's full statement.
 *
 * Everything the view is showing lives in the query string — which wallet, and
 * later which dates and which page. That is not decoration: it means a refresh
 * lands on the same rows, the back button walks back through what was actually
 * looked at, and the whole view can be sent to someone as a link.
 */
export function HistoryScreen() {
  const [params, setParams] = useSearchParams()
  const requested = params.get('account')

  const accounts = useResource(
    useCallback(() => listAccounts(), []),
    [],
  )

  const wallets = accounts.data ?? []

  /*
   * The wallet the URL asked for, while it is one of the caller's own.
   *
   * A link can name anything — a wallet since closed, or someone else's, which
   * the statement endpoint would refuse with 403. Falling back to the first
   * wallet shows something true instead of an error about a link the reader
   * probably did not write.
   */
  const account = wallets.find((wallet) => wallet.id === requested) ?? wallets[0] ?? null

  useEffect(() => {
    // Put the resolved wallet in the URL, so what is on screen and what the
    // address bar claims never disagree. Replaced rather than pushed: this is a
    // correction, not somewhere the reader navigated to.
    if (account !== null && requested !== account.id) {
      const next = new URLSearchParams(params)
      next.set('account', account.id)
      setParams(next, { replace: true })
    }
  }, [account, requested, params, setParams])

  const statement = useResource(
    useCallback(
      () =>
        account === null
          ? Promise.resolve(null)
          : getStatement(account.id, { page: 0, size: PAGE_SIZE }),
      [account],
    ),
    [account?.id],
  )

  const rows = statement.data?.content ?? []
  const loading = accounts.loading || statement.loading

  return (
    <div className="history">
      <div className="history__column">
        <AppHeader />
        <main className="history__main">
          <header className="history__header">
            <h1 className="title">Transactions</h1>
            {account !== null && (
              <p className="caption">{account.accountNumber}</p>
            )}
          </header>

          {accounts.error !== null && accounts.data === null && (
            <Notice tone={accounts.error.isRetryable ? 'retry' : 'error'}>
              {userMessage(accounts.error)}
              <span className="history__retry">
                <Button variant="secondary" onClick={accounts.reload}>
                  Try again
                </Button>
              </span>
            </Notice>
          )}

          {accounts.data !== null && wallets.length === 0 && (
            <section className="history__panel">
              <p className="body">You do not have a wallet yet.</p>
              <Link className="button button--primary" to="/">
                Go to your wallet
              </Link>
            </section>
          )}

          {loading && account === null && wallets.length === 0 && (
            <TransactionListPlaceholder rows={5} />
          )}

          {account !== null && (
            <>
              {statement.loading && <TransactionListPlaceholder rows={5} />}

              {!statement.loading && statement.error !== null && (
                <Notice tone={statement.error.isRetryable ? 'retry' : 'error'}>
                  {userMessage(statement.error)}
                  <span className="history__retry">
                    <Button variant="secondary" onClick={statement.reload}>
                      Try again
                    </Button>
                  </span>
                </Notice>
              )}

              {statement.data !== null && rows.length === 0 && (
                <p className="history__empty body">
                  Nothing has moved in this wallet yet.
                </p>
              )}

              {rows.length > 0 && <TransactionList entries={rows} />}
            </>
          )}

          <p className="history__back">
            <Link to="/">Back to your wallet</Link>
          </p>
        </main>
      </div>
    </div>
  )
}
