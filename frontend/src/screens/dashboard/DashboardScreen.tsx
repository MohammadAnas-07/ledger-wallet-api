import { useCallback, useState } from 'react'

import { createAccount, listAccounts } from '../../api/endpoints'
import { ApiError, userMessage } from '../../api/errors'
import type { AccountResponse, Uuid } from '../../api/types'
import { AppHeader } from '../../components/AppHeader'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { useResource } from '../../data/useResource'
import { formatAmount } from '../../format/money'

import { RecentTransactions } from './RecentTransactions'

import './dashboard-screen.css'

export function DashboardScreen() {
  const accounts = useResource(
    useCallback(() => listAccounts(), []),
    [],
  )

  /*
   * Which wallet the hero is showing.
   *
   * Held as an id and resolved against the list on every render rather than
   * held as an object. The list is reloaded after every movement of money, so
   * a stored object would go stale the moment a balance changed — and it would
   * survive the account being gone.
   */
  const [selectedId, setSelectedId] = useState<Uuid | null>(null)
  const selected = resolveSelected(accounts.data, selectedId)

  const [creating, setCreating] = useState(false)
  const [createError, setCreateError] = useState<ApiError | null>(null)

  async function createWallet() {
    setCreating(true)
    setCreateError(null)
    try {
      const created = await createAccount()
      // Selected explicitly: the new wallet is not necessarily first in the
      // list, and creating one is a request to look at it.
      setSelectedId(created.id)
      accounts.reload()
    } catch (cause) {
      setCreateError(
        cause instanceof ApiError
          ? cause
          : new ApiError({ code: 'UNEXPECTED_RESPONSE', status: 0 }),
      )
    } finally {
      setCreating(false)
    }
  }

  return (
    <div className="dashboard">
      <div className="dashboard__column">
        <AppHeader />
        <main className="dashboard__main">
          {accounts.loading && <LoadingPanel />}

          {!accounts.loading && accounts.error !== null && accounts.data === null && (
            <Notice tone={accounts.error.isRetryable ? 'retry' : 'error'}>
              {userMessage(accounts.error)}
              <span className="dashboard__retry">
                <Button variant="secondary" onClick={accounts.reload}>
                  Try again
                </Button>
              </span>
            </Notice>
          )}

          {/*
            * A failed refresh with a balance still on screen. The figure stays
            * visible because it was true a moment ago and is better than
            * nothing — but it is said plainly that it may no longer be.
            */}
          {accounts.error !== null && accounts.data !== null && (
            <Notice tone="retry">
              This balance may be out of date — it could not be refreshed.
            </Notice>
          )}

          {createError !== null && (
            <Notice tone={createError.isRetryable ? 'retry' : 'error'}>
              {userMessage(createError)}
            </Notice>
          )}

          {accounts.data !== null && accounts.data.length === 0 && (
            <EmptyWallets busy={creating} onCreate={createWallet} />
          )}

          {selected !== null && (
            <>
              <BalancePanel account={selected} stale={accounts.refreshing} />
              {/* Keyed by the account: switching wallets remounts this, so the
                  previous wallet's rows never sit under the new one's balance
                  while the request is in flight. */}
              <RecentTransactions key={selected.id} accountId={selected.id} />
              {accounts.data !== null && accounts.data.length > 1 && (
                <WalletSwitcher
                  accounts={accounts.data}
                  selectedId={selected.id}
                  onSelect={setSelectedId}
                />
              )}
              {/*
                * Secondary, and deliberately last on the page. Owning a second
                * wallet is a real thing this app supports — the switcher above
                * exists for it — and before this there was no way to reach it
                * except the empty state, which you only see once. It stays
                * secondary because the primary action on a wallet is moving
                * money, not acquiring somewhere to put it.
                */}
              <p className="dashboard__add">
                <Button
                  variant="secondary"
                  busy={creating}
                  busyLabel="Creating…"
                  onClick={createWallet}
                >
                  Add wallet
                </Button>
              </p>
            </>
          )}
        </main>
      </div>
    </div>
  )
}

/** The wallet the hero shows: the chosen one while it still exists, otherwise
 *  the first. Deriving it means a deleted or replaced account can never leave
 *  the screen pointing at nothing. */
function resolveSelected(
  accounts: AccountResponse[] | null,
  selectedId: Uuid | null,
): AccountResponse | null {
  if (accounts === null || accounts.length === 0) {
    return null
  }
  return accounts.find((account) => account.id === selectedId) ?? accounts[0]
}

function BalancePanel({
  account,
  stale,
}: {
  account: AccountResponse
  stale: boolean
}) {
  return (
    <section className="balance" aria-busy={stale || undefined}>
      <p className="caption">Balance</p>
      {/*
        * Neutral ink, never green. A balance is a fact, not good news —
        * design.md §2. Green belongs to a credit that just happened.
        */}
      <p className="hero-display balance__figure">{formatAmount(account.balance)}</p>
      <p className="caption">{account.accountNumber}</p>
      {account.status !== 'ACTIVE' && (
        <p className="balance__status caption">
          This wallet is {account.status.toLowerCase()}.
        </p>
      )}
    </section>
  )
}

function WalletSwitcher({
  accounts,
  selectedId,
  onSelect,
}: {
  accounts: AccountResponse[]
  selectedId: Uuid
  onSelect: (id: Uuid) => void
}) {
  return (
    <section className="wallets">
      <h2 className="caption wallets__heading">Your other wallets</h2>
      <ul className="wallets__list">
        {accounts
          .filter((account) => account.id !== selectedId)
          .map((account) => (
            <li key={account.id}>
              {/* Hairlines, not cards. Stacked shadows are noise; the elevation
                  is spent on the hero above. */}
              <button
                type="button"
                className="wallets__row"
                onClick={() => onSelect(account.id)}
              >
                <span className="body">{account.accountNumber}</span>
                <span className="amount amount--debit">
                  {formatAmount(account.balance)}
                </span>
              </button>
            </li>
          ))}
      </ul>
    </section>
  )
}

function EmptyWallets({
  busy,
  onCreate,
}: {
  busy: boolean
  onCreate: () => void
}) {
  return (
    <section className="empty">
      {/* A line of body text and one action, per design.md §6. No artwork. */}
      <p className="body">You do not have a wallet yet.</p>
      <Button busy={busy} busyLabel="Creating…" onClick={onCreate}>
        Create a wallet
      </Button>
    </section>
  )
}

/** Deliberately not a spinner. The shape of what is coming, held still — a
 *  spinner says "wait" and this says "a balance goes here". */
function LoadingPanel() {
  return (
    <section className="balance balance--loading" aria-busy="true">
      <p className="caption">Balance</p>
      <p className="hero-display balance__figure balance__figure--placeholder">
        —
      </p>
    </section>
  )
}
