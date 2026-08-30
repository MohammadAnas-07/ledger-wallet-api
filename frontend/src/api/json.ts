/*
 * JSON parsing that does not round the money.
 *
 * The backend serialises BigDecimal as a JSON number, so `{"balance":1250.00}`
 * arrives on the wire with its scale intact — and JSON.parse then turns it into
 * the double 1250, losing the scale and, past about fifteen digits, losing
 * accuracy outright. A ledger that is exact all the way through Postgres and
 * Java should not become approximate in the last three metres.
 *
 * So money fields are lifted out as their original text. Everything else parses
 * normally: page numbers, counts, and token lifetimes are all small integers
 * that a double represents exactly.
 */

/**
 * The BigDecimal-typed fields in the API, by name.
 *
 * A name-based list is narrow on purpose. Converting every number in a response
 * would turn `page`, `totalElements` and `expiresInSeconds` into strings too,
 * and those genuinely are numbers. Adding a money field to a DTO means adding
 * it here — which is why the list sits next to the types it mirrors.
 */
const MONEY_FIELDS = new Set([
  'amount',
  'balance',
  'balanceAfter',
  'fromBalanceAfter',
  'signedAmount',
])

/**
 * Whether this engine can hand a reviver the raw source text of a number
 * (the JSON.parse source-access proposal).
 *
 * Where it exists, money survives byte for byte. Where it does not, the number
 * has already been through a double by the time anything can intervene, and the
 * best available answer is its decimal form — correct for every amount inside
 * ±9,007,199,254,740,991, which is every amount this app will realistically
 * show, and not correct for the largest ones BigDecimal(19,2) permits.
 */
const SUPPORTS_SOURCE_ACCESS = (() => {
  try {
    let seen: string | undefined
    JSON.parse('{"n":1.10}', function (_key, _value, context?: { source?: string }) {
      if (context && typeof context.source === 'string') {
        seen = context.source
      }
      return _value
    })
    return seen === '1.10'
  } catch {
    return false
  }
})()

/** True when money is being read exactly; false when it is going through a
 *  double first. Exposed so this is observable rather than a silent
 *  difference — and so a test can assert which path it took. */
export const parsesMoneyExactly = SUPPORTS_SOURCE_ACCESS

/**
 * Parse a JSON response body, keeping money fields as their literal text.
 *
 * @throws SyntaxError when the body is not JSON at all — which is a real case,
 *   not a theoretical one: a misconfigured dev proxy answers with somebody
 *   else's HTML.
 */
export function parseJson<T>(text: string): T {
  return JSON.parse(text, function (
    key: string,
    value: unknown,
    context?: { source?: string },
  ) {
    if (typeof value !== 'number' || !MONEY_FIELDS.has(key)) {
      return value
    }
    if (context && typeof context.source === 'string') {
      return context.source
    }
    return String(value)
  }) as T
}
