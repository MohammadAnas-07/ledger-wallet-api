import { Link } from 'react-router'

import { AppHeader } from '../../components/AppHeader'

import './transfer-screen.css'

/**
 * The form itself arrives in chunk 3.2. This chunk is the route it lives at.
 *
 * Kept in the same one-column shell as the dashboard so that navigating between
 * them moves the content and nothing else — the header stays put, the column
 * keeps its width, and the page does not appear to jump.
 */
export function TransferScreen() {
  return (
    <div className="transfer">
      <div className="transfer__column">
        <AppHeader />
        <main className="transfer__main">
          <section className="transfer__panel">
            <h1 className="title">Send money</h1>
            <p className="body transfer__note">
              The form arrives in the next chunk.
            </p>
            <Link className="transfer__back" to="/">
              Back to your wallet
            </Link>
          </section>
        </main>
      </div>
    </div>
  )
}
