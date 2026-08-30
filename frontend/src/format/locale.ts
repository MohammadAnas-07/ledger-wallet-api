/**
 * One locale for the whole app.
 *
 * Amounts and dates formatted against different locales is the kind of drift
 * nobody notices until a screen reads `1,250.00` next to `30/08/2026` — two
 * conventions that do not belong to the same place. Both formatters read this.
 */
export const LOCALE = 'en-US'
