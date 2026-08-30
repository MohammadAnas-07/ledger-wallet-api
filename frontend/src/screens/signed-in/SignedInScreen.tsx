import { Button } from '../../components/Button'
import { useAuth } from '../../auth/useAuth'

import './signed-in-screen.css'

/**
 * A placeholder, and honest about it.
 *
 * Feature 1 ends with the session working; the balance hero and the transaction
 * list are Feature 2. What this screen does carry is real: the profile came
 * from `/auth/me` behind a bearer token, and signing out actually ends the
 * session rather than hiding a screen.
 */
export function SignedInScreen() {
  const { user, signOut } = useAuth()

  return (
    <main className="signed-in">
      <div className="signed-in__panel">
        <h1 className="title">Signed in</h1>
        <p className="body">{user?.fullName}</p>
        <p className="caption">{user?.email}</p>
        <p className="caption signed-in__note">
          The balance and your recent transactions arrive in Feature 2.
        </p>
        <Button variant="secondary" onClick={signOut}>
          Sign out
        </Button>
      </div>
    </main>
  )
}
