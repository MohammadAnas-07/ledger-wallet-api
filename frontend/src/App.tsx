import './foundation-check.css'

/*
 * Temporary — the chunk 1.1 foundation check.
 *
 * It exists so the tokens can be looked at in a browser: that Inter actually
 * loaded, that the palette resolves, that the type scale is the one design.md
 * describes, and that tabular numerals really do line the decimal points up.
 *
 * Nothing here is a component and nothing should be imported from it. The whole
 * file, and foundation-check.css beside it, is deleted when the Login screen
 * lands in chunk 1.3.
 */

const PALETTE = [
  { token: '--canvas', hex: '#ffffff', job: 'Page and card surfaces' },
  { token: '--parchment', hex: '#f5f5f7', job: 'Recessed sections, input fills' },
  { token: '--ink', hex: '#1d1d1f', job: 'Primary text, and every amount' },
  { token: '--ink-muted', hex: '#6e6e73', job: 'Labels, counterparty names' },
  { token: '--ink-subtle', hex: '#86868b', job: 'Timestamps, placeholders' },
  { token: '--separator', hex: '#d2d2d7', job: 'Hairlines, input borders' },
  { token: '--action', hex: '#0066cc', job: 'Interactive affordances only' },
  { token: '--credit', hex: '#1d8a4e', job: 'Credit amounts only' },
  { token: '--error', hex: '#d70015', job: 'Validation and failure only' },
]

// Deliberately ragged in magnitude: 1.00 next to 128,400.00 is where
// proportional digits would visibly fail to stack.
const AMOUNTS = [
  { label: 'Deposit', amount: '+1,250.00', credit: true },
  { label: 'Transfer to 4471 0092', amount: '−128,400.00', credit: false },
  { label: 'Transfer from 8810 3345', amount: '+64.05', credit: true },
  { label: 'Withdrawal', amount: '−1.00', credit: false },
]

export default function App() {
  return (
    <main className="check">
      <header className="check__header">
        <h1 className="title">Foundation check</h1>
        <p className="caption">
          Chunk 1.1 — tokens, type scale, and numerals. Deleted in chunk 1.3.
        </p>
      </header>

      <section className="check__section">
        <h2 className="check__label caption">Palette</h2>
        <ul className="swatches">
          {PALETTE.map(({ token, hex, job }) => (
            <li className="swatch" key={token}>
              <span
                className="swatch__chip"
                style={{ background: `var(${token})` }}
              />
              <span className="swatch__token body">{token}</span>
              <span className="swatch__hex caption">{hex}</span>
              <span className="swatch__job caption">{job}</span>
            </li>
          ))}
        </ul>
      </section>

      <section className="check__section">
        <h2 className="check__label caption">Type scale</h2>
        <p className="hero-display">2,480.00</p>
        <p className="title">Recent transactions</p>
        <p className="body">
          A wallet screen is read, not browsed.
        </p>
        <p className="caption">30 August 2026 at 14:02</p>
      </section>

      <section className="check__section">
        <h2 className="check__label caption">
          Tabular numerals — decimal points stack
        </h2>
        <ul className="rows">
          {AMOUNTS.map(({ label, amount, credit }) => (
            <li className="row" key={label}>
              <span className="body">{label}</span>
              <span
                className={`amount ${credit ? 'amount--credit' : 'amount--debit'}`}
              >
                {amount}
              </span>
            </li>
          ))}
        </ul>
      </section>
    </main>
  )
}
