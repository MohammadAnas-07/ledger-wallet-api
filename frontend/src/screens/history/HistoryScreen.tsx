import { useCallback, useEffect } from 'react'
import { Link, useSearchParams } from 'react-router'

import { getStatement, listAccounts } from '../../api/endpoints'
import { userMessage } from '../../api/errors'
import type { AccountResponse, Uuid } from '../../api/types'
import { AppHeader } from '../../components/AppHeader'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
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

/** `YYYY-MM-DD`, which is what a date input reads and writes. */
const DATE_SHAPE = /^\d{4}-\d{2}-\d{2}$/

/**
 * A calendar day the reader picked, as the instant that day begins for them.
 *
 * Parsed without a zone suffix on purpose, which makes it local midnight rather
 * than UTC midnight. The rows show local time, so a filter that meant something
 * else would quietly disagree with the timestamps beside it — near midnight,
 * by a whole day.
 */
function startOfDay(day: string): string | undefined {
  const at = new Date(`${day}T00:00:00`)
  return Number.isNaN(at.getTime()) ? undefined : at.toISOString()
}

/**
 * The same day's last instant.
 *
 * The backend treats `to` as inclusive but takes an instant, so sending the
 * day's midnight would ask for a range that ends before the day begins —
 * picking a single day would return nothing at all, on a day with
 * transactions in it.
 */
function endOfDay(day: string): string | undefined {
  const at = new Date(`${day}T23:59:59.999`)
  return Number.isNaN(at.getTime()) ? undefined : at.toISOString()
}

/** A date from the URL, ignoring anything that is not one. */
function readDay(params: URLSearchParams, name: string): string {
  const raw = params.get(name) ?? ''
  return DATE_SHAPE.test(raw) ? raw : ''
}

/**
 * One wallet's full statement.
 *
 * Everything the view is showing lives in the query string — which wallet,
 * which dates, which page. That is not decoration: it means a refresh lands on
 * the same rows, the back button walks back through what was actually looked
 * at, and the whole view can be sent to someone as a link.
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

  const page = readPage(params)
  const from = readDay(params, 'from')
  const to = readDay(params, 'to')

  const filtered = from !== '' || to !== ''
  /* A range that ends before it starts returns nothing, which reads as "this
   * wallet is empty" rather than "these dates are the wrong way round". Named
   * here instead, and the request is not sent. */
  const backwards = from !== '' && to !== '' && from > to

  const statement = useResource(
    useCallback(
      () =>
        account === null || backwards
          ? Promise.resolve(null)
          : getStatement(account.id, {
              page,
              size: PAGE_SIZE,
              from: from === '' ? undefined : startOfDay(from),
              to: to === '' ? undefined : endOfDay(to),
            }),
      [account, page, from, to, backwards],
    ),
    [account?.id, page, from, to, backwards],
  )

  /**
   * Every filter change starts a new list, so it starts at its first page.
   *
   * Without this, narrowing to a week while sitting on page three asks for the
   * third page of a list that now has one — an empty screen on a wallet that
   * has exactly what was asked for.
   */
  function changeFilter(mutate: (next: URLSearchParams) => void) {
    const next = new URLSearchParams(params)
    mutate(next)
    next.delete('page')
    setParams(next)
    window.scrollTo({ top: 0 })
  }

  const rows = statement.data?.content ?? []
  const loading = accounts.loading || statement.loading
  const totalPages = statement.data?.totalPages ?? 0
  const totalEntries = statement.data?.totalElements ?? 0

  useEffect(() => {
    /*
     * One rule for the page parameter: the address bar says the page that is
     * actually showing, or says nothing.
     *
     * Two things break that. A number past the end — from a link written when
     * there was more history — shows an empty list on a wallet that has
     * transactions, which reads as "nothing here" rather than "you have gone
     * too far". And anything that is not a page number at all falls back to the
     * first page while `?page=abc` sits in the URL claiming otherwise.
     *
     * Replaced rather than pushed: this is a correction, and going back should
     * not land on the value that was just corrected away.
     */
    const clamped =
      totalPages > 0 ? Math.min(page, totalPages - 1) : page
    const canonical = clamped === 0 ? null : String(clamped)

    if (params.get('page') !== canonical) {
      const next = new URLSearchParams(params)
      if (canonical === null) {
        next.delete('page')
      } else {
        next.set('page', canonical)
      }
      setParams(next, { replace: true })
    }
  }, [page, totalPages, params, setParams])

  function goToPage(next: number) {
    const updated = new URLSearchParams(params)
    // Page zero is the default, so it stays out of the URL rather than
    // sitting there as ?page=0 on every link anyone copies.
    if (next === 0) {
      updated.delete('page')
    } else {
      updated.set('page', String(next))
    }
    // Pushed, not replaced: paging is navigation, and the back button should
    // walk back through the pages actually read.
    setParams(updated)
    // A new page starts at its own top. Without this, pressing Next at the
    // bottom of one page leaves the reader at the bottom of the next.
    window.scrollTo({ top: 0 })
  }

  return (
    <div className="history">
      <div className="history__column">
        <AppHeader />
        <main className="history__main">
          <header className="history__header">
            <h1 className="title">Transactions</h1>
          </header>

          {account !== null && (
            <FilterBar
              wallets={wallets}
              accountId={account.id}
              from={from}
              to={to}
              backwards={backwards}
              filtered={filtered}
              onChange={changeFilter}
            />
          )}

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
                /*
                 * Two different emptinesses, and saying the wrong one sends the
                 * reader looking for a problem that is not there. A wallet with
                 * no history has nothing to find; a wallet with history and a
                 * range that misses it has plenty, just not here.
                 */
                <p className="history__empty body">
                  {filtered
                    ? 'No transactions in this date range.'
                    : 'Nothing has moved in this wallet yet.'}
                </p>
              )}

              {rows.length > 0 && (
                <>
                  <TransactionList entries={rows} />
                  <Pager
                    page={page}
                    totalPages={totalPages}
                    totalEntries={totalEntries}
                    busy={statement.refreshing}
                    onGo={goToPage}
                  />
                </>
              )}
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

/**
 * Which wallet, and between which dates.
 *
 * design.md §5 asks for one unit: the same surface across the wallet control
 * and both date inputs, so the bar reads as a single thing rather than three
 * controls that happen to sit together.
 *
 * Native date inputs, for the same reasons the select is native — the browser's
 * own picker, keyboard entry, and the platform picker on a phone. A hand-built
 * calendar has to earn all three back before it is worth anything.
 */
function FilterBar({
  wallets,
  accountId,
  from,
  to,
  backwards,
  filtered,
  onChange,
}: {
  wallets: AccountResponse[]
  accountId: Uuid
  from: string
  to: string
  backwards: boolean
  filtered: boolean
  onChange: (mutate: (next: URLSearchParams) => void) => void
}) {
  return (
    <section className="filters">
      {/*
        * On its own line, and not only because three controls do not fit: the
        * wallet chooses what is being read, where the dates narrow it. Putting
        * them side by side reads as three equal filters, which they are not.
        */}
      <div className="filters__wallet">
        <SelectField
          label="Wallet"
          value={accountId}
          onChange={(event) =>
            onChange((next) => next.set('account', event.target.value))
          }
        >
          {wallets.map((wallet) => (
            <option key={wallet.id} value={wallet.id}>
              {wallet.accountNumber}
            </option>
          ))}
        </SelectField>
      </div>

      <div className="filters__row">
        <TextField
          label="From"
          type="date"
          value={from}
          // An empty date input clears the parameter rather than writing an
          // empty one, so a cleared filter leaves no trace in the URL.
          onChange={(event) =>
            onChange((next) =>
              event.target.value === ''
                ? next.delete('from')
                : next.set('from', event.target.value),
            )
          }
        />

        <TextField
          label="To"
          type="date"
          value={to}
          error={backwards ? 'This is before the From date' : undefined}
          onChange={(event) =>
            onChange((next) =>
              event.target.value === ''
                ? next.delete('to')
                : next.set('to', event.target.value),
            )
          }
        />
      </div>

      {/* Only once there is something to clear. A permanently visible Clear on
          an unfiltered list is a control that does nothing most of the time. */}
      {filtered && (
        <button
          type="button"
          className="filters__clear"
          onClick={() =>
            onChange((next) => {
              next.delete('from')
              next.delete('to')
            })
          }
        >
          Clear dates
        </button>
      )}
    </section>
  )
}

/**
 * Which page, out of how many, and how much there is in total.
 *
 * The count is the part that earns its place: "20 of 26" tells a reader whether
 * they are looking at a month or at everything, which a bare Next button never
 * does.
 */
function Pager({
  page,
  totalPages,
  totalEntries,
  busy,
  onGo,
}: {
  page: number
  totalPages: number
  totalEntries: number
  busy: boolean
  onGo: (page: number) => void
}) {
  // One page is not a sequence. The count still belongs, but nothing to press.
  const paged = totalPages > 1

  return (
    <div className="pager">
      <p className="caption pager__count">
        {totalEntries} transaction{totalEntries === 1 ? '' : 's'}
        {paged && ` · page ${page + 1} of ${totalPages}`}
      </p>

      {paged && (
        <div className="pager__controls">
          <Button
            variant="secondary"
            disabled={page === 0 || busy}
            onClick={() => onGo(page - 1)}
          >
            Previous
          </Button>
          <Button
            variant="secondary"
            disabled={page >= totalPages - 1 || busy}
            onClick={() => onGo(page + 1)}
          >
            Next
          </Button>
        </div>
      )}
    </div>
  )
}

/** The page from the URL, ignoring anything that is not a page number. A
 *  hand-edited `?page=abc` should show the first page, not crash. */
function readPage(params: URLSearchParams): number {
  const raw = Number(params.get('page'))
  return Number.isInteger(raw) && raw >= 0 ? raw : 0
}
