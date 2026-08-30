/*
 * Timestamps, for the caption line under a transaction.
 *
 * Two decisions worth stating, because both are easy to get wrong in a ledger:
 *
 *   - 24-hour, always. `14:02` cannot be misread; `2:02` without the meridiem
 *     visible can, and a statement is a record people check against their own
 *     memory of when something happened.
 *   - the viewer's own time zone, not UTC. The backend stores instants and the
 *     API sends them as ISO with an offset, so the conversion is unambiguous;
 *     showing UTC would be showing a time nobody experienced.
 */

import type { IsoInstant } from '../api/types'

import { LOCALE } from './locale'

/** `"2026-08-30T14:02:10Z"` → `"30 Aug 2026, 14:02"`. */
export function formatTimestamp(instant: IsoInstant, timeZone?: string): string {
  return format(instant, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone,
  })
}

/** `"2026-08-30T14:02:10Z"` → `"30 Aug 2026"`. For grouping and date filters. */
export function formatDate(instant: IsoInstant, timeZone?: string): string {
  return format(instant, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone,
  })
}

function format(instant: IsoInstant, options: Intl.DateTimeFormatOptions): string {
  const date = new Date(instant)

  if (Number.isNaN(date.getTime())) {
    /*
     * Unparseable. Return what the server sent rather than the string
     * "Invalid Date": the raw value is at least evidence of what went wrong,
     * and it is the timestamp on a money movement — silently blanking it would
     * be worse than showing something ugly.
     */
    return instant
  }

  // `numeric` day with `short` month renders as "Aug 30, 2026" in en-US. The
  // parts are reordered by hand because day-first reads better beside an
  // amount, and because a locale switch should not silently reorder a ledger.
  const parts = new Intl.DateTimeFormat(LOCALE, options).formatToParts(date)
  const of = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((part) => part.type === type)?.value ?? ''

  const day = `${of('day')} ${of('month')} ${of('year')}`
  const hour = of('hour')

  return hour === '' ? day : `${day}, ${hour}:${of('minute')}`
}
