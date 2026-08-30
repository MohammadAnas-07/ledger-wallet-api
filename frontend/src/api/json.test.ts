import { describe, expect, it } from 'vitest'

import { parseJson, parsesMoneyExactly } from './json'

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
