import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

import { MONEY_FIELDS, parseJson, parsesMoneyExactly } from './json'

interface Sample {
  balance: string
  amount: string
  page: number
  hasNext: boolean
  accountNumber: string
}

describe('parseJson', () => {
  it('keeps money fields as text, with their scale intact', () => {
    const parsed = parseJson<Sample>(
      '{"balance":1250.00,"amount":0.50,"page":0,"hasNext":false,"accountNumber":"4471 0092"}',
    )

    expect(parsed.balance).toBe('1250.00')
    expect(parsed.amount).toBe('0.50')
    expect(parsed.accountNumber).toBe('4471 0092')
  })

  it('leaves non-money numbers as numbers', () => {
    // page, size, totalElements and expiresInSeconds are genuinely numbers;
    // turning every number into a string would break them.
    const parsed = parseJson<Sample>('{"page":3,"hasNext":true}' as string)
    expect(parsed.page).toBe(3)
    expect(parsed.hasNext).toBe(true)
  })

  it('does not round the largest amounts the backend permits', () => {
    // This is the assertion the whole module exists for. Through JSON.parse
    // alone this value comes back as 100000000000000000.
    const parsed = parseJson<Sample>('{"balance":99999999999999999.99}')

    if (parsesMoneyExactly) {
      expect(parsed.balance).toBe('99999999999999999.99')
    } else {
      // Documented degradation: without source access the number has already
      // been through a double before anything here can intervene.
      expect(parsed.balance).toBe('100000000000000000')
    }
  })

  it('handles money nested in arrays and objects', () => {
    const parsed = parseJson<{ content: { amount: string }[] }>(
      '{"content":[{"amount":10.00},{"amount":2.50}]}',
    )
    expect(parsed.content.map((e) => e.amount)).toEqual(['10.00', '2.50'])
  })

  it('throws on a body that is not JSON', () => {
    // Not theoretical: a dev proxy pointing at the wrong port answers with
    // somebody else's HTML.
    expect(() => parseJson('<!DOCTYPE HTML><html>404</html>')).toThrow()
  })
})

/*
 * The guard the comment in json.ts cannot be.
 *
 * MONEY_FIELDS is a hand-written list, and a money field left out of it does not
 * fail to compile and does not fail any other test — it just quietly arrives as
 * a rounded double, on a screen showing somebody's balance. TypeScript cannot
 * catch it either, because the types are gone by the time this code runs.
 *
 * So the source is read as text. It is a blunt instrument, and it is the only
 * one that actually fails when someone adds `fee: Money` to a DTO and stops
 * there.
 */
describe('MONEY_FIELDS', () => {
  const source = readFileSync(
    fileURLToPath(new URL('./types.ts', import.meta.url)),
    'utf8',
  )

  // Matches a property declaration whose type is Money: `  balance: Money`,
  // including optional (`amount?: Money`) and unioned (`fee: Money | null`).
  const declared = new Set(
    [...source.matchAll(/^\s+(\w+)\??:\s*Money\b/gm)].map((match) => match[1]),
  )

  it('finds the money fields in types.ts at all', () => {
    // If the regex stops matching — the file is reformatted, Money is renamed —
    // every other assertion here passes vacuously. This is the canary.
    expect(declared.size).toBeGreaterThan(3)
  })

  it('covers every field typed as Money', () => {
    const missing = [...declared].filter((field) => !MONEY_FIELDS.has(field))
    expect(missing, `add these to MONEY_FIELDS in json.ts: ${missing.join(', ')}`)
      .toEqual([])
  })

  it('carries nothing that is no longer a money field', () => {
    // A stale name is harmless at runtime but misleading to read, and it hides
    // that a field was renamed rather than removed.
    const stale = [...MONEY_FIELDS].filter((field) => !declared.has(field))
    expect(stale, `remove these from MONEY_FIELDS in json.ts: ${stale.join(', ')}`)
      .toEqual([])
  })
})
