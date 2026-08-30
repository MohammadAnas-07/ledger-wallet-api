import { describe, expect, it } from 'vitest'

import { formatAmount, formatSignedAmount, isValidAmount } from './money'

describe('formatAmount', () => {
  it('always renders two decimal places', () => {
    // The ledger column is DECIMAL(19,2). Rendering 1250 or 1250.5 would be
    // saying something the ledger did not say.
    expect(formatAmount('1250')).toBe('1,250.00')
    expect(formatAmount('1250.5')).toBe('1,250.50')
    expect(formatAmount('1250.00')).toBe('1,250.00')
    expect(formatAmount('0')).toBe('0.00')
    expect(formatAmount('0.07')).toBe('0.07')
  })

  it('groups thousands', () => {
    expect(formatAmount('999.99')).toBe('999.99')
    expect(formatAmount('1000')).toBe('1,000.00')
    expect(formatAmount('128400.5')).toBe('128,400.50')
  })

  it('keeps the largest amounts the backend accepts exact', () => {
    // Seventeen integer digits is what @Digits(integer = 17) permits, and it is
    // past what a double holds exactly — the whole reason money crosses this
    // layer as text. Through Number() this comes back as 99,999,999,999,999,996.
    expect(formatAmount('99999999999999999.99')).toBe(
      '99,999,999,999,999,999.99',
    )
  })

  it('drops the sign, which the caller adds back deliberately', () => {
    expect(formatAmount('-40')).toBe('40.00')
  })

  it('truncates rather than rounds beyond scale 2', () => {
    // Only reachable if the contract is violated. Truncating reports less than
    // was there; rounding would report a figure the ledger never held.
    expect(formatAmount('1.999')).toBe('1.99')
  })
})

describe('formatSignedAmount', () => {
  it('signs by direction, with a real minus', () => {
    expect(formatSignedAmount('1250', 'CREDIT')).toBe('+1,250.00')
    // U+2212, not a hyphen: it aligns with tabular figures.
    expect(formatSignedAmount('1250', 'DEBIT')).toBe('−1,250.00')
  })
})

describe('isValidAmount', () => {
  it('accepts what the backend accepts', () => {
    expect(isValidAmount('1')).toBe(true)
    expect(isValidAmount('0.01')).toBe(true)
    expect(isValidAmount(' 250.00 ')).toBe(true)
    expect(isValidAmount('99999999999999999.99')).toBe(true)
  })

  it('rejects what it does not', () => {
    expect(isValidAmount('')).toBe(false)
    expect(isValidAmount('0')).toBe(false) // @Positive
    expect(isValidAmount('0.00')).toBe(false)
    expect(isValidAmount('-5')).toBe(false)
    expect(isValidAmount('1.234')).toBe(false) // scale 2
    expect(isValidAmount('1e3')).toBe(false)
    expect(isValidAmount('abc')).toBe(false)
    expect(isValidAmount('999999999999999999')).toBe(false) // 18 digits
  })
})
