import { describe, expect, it } from 'vitest'

import { formatDate, formatTimestamp } from './datetime'

/*
 * Every assertion pins a time zone. Without one these tests pass or fail
 * depending on where the machine running them happens to be, which is the kind
 * of test that goes red in CI and green on the laptop.
 */
describe('formatTimestamp', () => {
  it('renders day first, 24-hour', () => {
    expect(formatTimestamp('2026-08-30T14:02:10Z', 'UTC')).toBe(
      '30 Aug 2026, 14:02',
    )
  })

  it('does not fold the afternoon into a 12-hour clock', () => {
    // 23:45 must never render as 11:45.
    expect(formatTimestamp('2026-01-05T23:45:00Z', 'UTC')).toBe(
      '5 Jan 2026, 23:45',
    )
  })

  it('keeps midnight at 00, not 24', () => {
    expect(formatTimestamp('2026-01-05T00:07:00Z', 'UTC')).toBe(
      '5 Jan 2026, 00:07',
    )
  })

  it('converts into the viewer time zone, including across the date line', () => {
    // The same instant is the next day in Kolkata. A statement that showed the
    // UTC date would disagree with the user's memory of when it happened.
    expect(formatTimestamp('2026-08-30T21:30:00Z', 'Asia/Kolkata')).toBe(
      '31 Aug 2026, 03:00',
    )
  })

  it('returns the raw value when the instant cannot be parsed', () => {
    // Better evidence than "Invalid Date", and better than a blank timestamp
    // on a movement of money.
    expect(formatTimestamp('not-a-date', 'UTC')).toBe('not-a-date')
  })
})

describe('formatDate', () => {
  it('drops the time', () => {
    expect(formatDate('2026-08-30T14:02:10Z', 'UTC')).toBe('30 Aug 2026')
  })

  it('returns the raw value when the instant cannot be parsed', () => {
    expect(formatDate('', 'UTC')).toBe('')
  })
})
