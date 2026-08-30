import type {
  AccountResponse,
  StatementEntryResponse,
  Uuid,
} from '../../api/types'

/**
 * Someone this wallet has already dealt with.
 *
 * The whole reason this exists: the API takes a destination as a UUID, and
 * offers no way to turn an account number into one. So the only accounts a
 * sender can be offered are the ones whose ids the app has already been told —
 * their own wallets, and the counterparties recorded on their own statement.
 */
export interface Counterparty {
  accountId: Uuid
  accountNumber: string
}

/**
 * Reads a statement and returns the distinct accounts on the other side of it.
 *
 * Deposits and withdrawals contribute nothing: their counterparty is the system
 * account, which the API withholds by design, and which nobody can send to.
 *
 * The caller's own wallets are removed as well. They belong in the list, but
 * they are already there under their own heading, with a live balance beside
 * them — and a wallet appearing twice in one dropdown is a wallet the user has
 * to think about twice.
 */
export function counterpartiesFrom(
  entries: StatementEntryResponse[],
  own: AccountResponse[],
): Counterparty[] {
  const mine = new Set(own.map((account) => account.id))
  const seen = new Set<Uuid>()
  const found: Counterparty[] = []

  for (const entry of entries) {
    const other = entry.counterparty
    if (other === null || mine.has(other.accountId) || seen.has(other.accountId)) {
      continue
    }
    seen.add(other.accountId)
    found.push({ accountId: other.accountId, accountNumber: other.accountNumber })
  }

  // Statement order is newest first, so this comes back most-recently-dealt-with
  // first — which is the order someone is most likely to want.
  return found
}
