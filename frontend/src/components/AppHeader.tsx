import { useAuth } from '../auth/useAuth'

import './app-header.css'

/**
 * The only chrome in the app.
 *
 * Sign out is a link, not a button: design.md §5 allows one primary action per
 * screen, and on a wallet that action is never "leave". The name beside it is
 * there so a shared machine shows whose money this is before anyone reads a
 * figure.
 */
export function AppHeader() {
  const { user, signOut } = useAuth()

  return (
    <header className="app-header">
      <p className="app-header__mark body">Wallet</p>
      <div className="app-header__account">
        <span className="caption">{user?.fullName}</span>
        <button type="button" className="app-header__signout" onClick={signOut}>
          Sign out
        </button>
      </div>
    </header>
  )
}
