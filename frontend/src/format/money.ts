/*
 * Turning ledger amounts into something a person reads.
 *
 * Two rules from design.md §3, and everything here exists to keep them:
 *
 *   - always two decimal places. `1,250.00`, never `1250` and never `1250.5`.
 *     The backend's column is DECIMAL(19,2); a figure that renders with one
 *     decimal place is telling the user something the ledger did not say.
 *   - group the thousands, so a column of amounts can be compared at a glance.
 *     Tabular numerals in base.css do the rest of that job.
 *
 * The input is the exact text the backend sent (see Money in api/types). These
 * functions read that text; they never do arithmetic on it, which is what keeps
 * a double out of the middle of a wallet.
 */

import type { EntryDirection, Money } from '../api/types'

import { LOCALE } from './locale'

const GROUPER = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 0 })

/**
 * `"1250.5"` → `"1,250.50"`, `"-40"` → `"40.00"`.
 *
 * The sign is dropped: direction is shown by an explicit `+` or `−` chosen by
 * the caller, so an amount that carries its own minus would render `−−40.00`.
 * See {@link formatSignedAmount}.
 */
export function formatAmount(amount: Money): string {
  const [, digits, fraction] = split(amount)

  // Intl groups the integer part correctly for anything a double holds exactly,
  // but the integer part here can be seventeen digits — beyond that range. So
  // the grouping is applied to the digits as text, and the fraction is carried
  // across untouched rather than being reconstructed from a number.
  return `${group(digits)}.${fraction}`
}

/**
 * The same figure with the sign the statement should show.
 *
 * A minus, not a hyphen: `−` (U+2212) is the character the digits were designed
 * with, and it aligns with them. A hyphen sits too high and too short next to
 * tabular figures.
 */
export function formatSignedAmount(
  amount: Money,
  direction: EntryDirection,
): string {
  return `${direction === 'CREDIT' ? '+' : '−'}${formatAmount(amount)}`
}

/**
 * Whether an amount is usable as money: a decimal with at most two places, and
 * within the range the backend's own validation accepts.
 *
 * Used by forms before a request is made. It is not a substitute for the
 * server's validation — it is what stops an obviously wrong amount from costing
 * a round trip, and what lets the field say so while the user is still in it.
 */
export function isValidAmount(input: string): boolean {
  const trimmed = input.trim()
  if (!/^\d{1,17}(\.\d{1,2})?$/.test(trimmed)) {
    return false
  }
  // Positive, per the backend's @Positive: zero is not a movement of money.
  return /[1-9]/.test(trimmed)
}

/** `"-1250.5"` → `['-', '1250', '50']`. Text only, no arithmetic. */
function split(amount: Money): [string, string, string] {
  const trimmed = amount.trim()
  const negative = trimmed.startsWith('-')
  const unsigned = negative ? trimmed.slice(1) : trimmed

  const point = unsigned.indexOf('.')
  const digits = point === -1 ? unsigned : unsigned.slice(0, point)
  const fraction = point === -1 ? '' : unsigned.slice(point + 1)

  return [
    negative ? '-' : '',
    digits === '' ? '0' : digits,
    // Pad, then cut. A scale of 1 is padded to `.50`; a scale of 3 would be a
    // contract violation, and truncating is the honest response — rounding here
    // would invent a figure the ledger never held.
    (fraction + '00').slice(0, 2),
  ]
}

function group(digits: string): string {
  // Intl handles the common case and knows the locale's separator; it is only
  // wrong past 15 digits, where the value cannot survive a double.
  if (digits.length <= 15) {
    return GROUPER.format(Number(digits))
  }
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}
