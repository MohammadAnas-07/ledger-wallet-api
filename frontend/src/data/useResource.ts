import { useCallback, useEffect, useRef, useState } from 'react'
import type { DependencyList } from 'react'

import { ApiError } from '../api/errors'

/*
 * One way to read something from the API.
 *
 * Every screen in this app needs the same four things — is it loading, did it
 * fail, is it being refreshed, and how do I ask again — and four screens each
 * inventing that is four chances to get the last one wrong.
 */

export interface Resource<T> {
  /** Null until the first load succeeds. Kept across a failed refresh. */
  data: T | null
  error: ApiError | null
  /** First load, with nothing on screen yet. */
  loading: boolean
  /** Loading again with data already showing. A different state because it is
   *  a different screen: a refresh must not blank what the user is reading. */
  refreshing: boolean
  reload: () => void
}

export function useResource<T>(
  load: () => Promise<T>,
  deps: DependencyList,
): Resource<T> {
  const [data, setData] = useState<T | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)

  /*
   * The loader is a closure and gets a new identity every render, so it is held
   * in a ref and never in the dependency list. What triggers a reload is `deps`,
   * exactly as it would be for the useEffect this replaces — the caller says
   * what the request depends on, which is the only thing it can know.
   */
  const loadRef = useRef(load)
  useEffect(() => {
    loadRef.current = load
  })

  /*
   * Every request takes a ticket. Only the newest one may write state.
   *
   * This is not theoretical here: selecting a second wallet while the first
   * one's statement is still in flight is one click, and without this the slower
   * response wins and the screen shows one account's balance above another
   * account's transactions.
   */
  const ticket = useRef(0)

  const run = useCallback(() => {
    const mine = ++ticket.current

    setError(null)
    // Which of the two loading states this is depends on whether there is
    // anything on screen to preserve.
    setData((current) => {
      if (current === null) {
        setLoading(true)
      } else {
        setRefreshing(true)
      }
      return current
    })

    loadRef.current().then(
      (result) => {
        if (mine !== ticket.current) {
          return
        }
        setData(result)
        setLoading(false)
        setRefreshing(false)
      },
      (cause: unknown) => {
        if (mine !== ticket.current) {
          return
        }
        /*
         * The data already on screen is deliberately kept. A failed refresh
         * leaves the last known balance visible with an error beside it, which
         * is more useful than an empty panel — and the screen is the one that
         * decides how loudly to say the figure may be stale.
         *
         * A 401 never really surfaces here: the client ends the session, the
         * provider signs out, and this component unmounts before anything can
         * render the error.
         */
        setError(toApiError(cause))
        setLoading(false)
        setRefreshing(false)
      },
    )
  }, [])

  useEffect(() => {
    run()

    // Invalidating the ticket on cleanup does two jobs: it drops the response
    // to a request whose dependencies have already changed, and it stops a
    // resolved promise writing state into a component that is gone.
    return () => {
      // Reading the ref's current value in a cleanup is what the rule warns
      // about, and here it is the entire point. That warning is written for
      // refs holding DOM nodes, where the value at cleanup time is probably
      // not the one the effect saw. This ref is a counter, and its latest
      // value is precisely the request that needs cancelling.
      // oxlint-disable-next-line react-hooks/exhaustive-deps
      ticket.current++
    }
    // `deps` is passed straight through: what this request depends on is the
    // caller's contract, exactly as it is for the useEffect this stands in for.
    // The rule cannot verify a list it cannot see, which is inherent to wrapping
    // useEffect at all — the check moves to the call sites, where the list is a
    // literal again.
    // oxlint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  return { data, error, loading, refreshing, reload: run }
}

/** Anything that is not an ApiError escaped the client, which is a bug there —
 *  but a screen still has to render something rather than crash. */
function toApiError(cause: unknown): ApiError {
  return cause instanceof ApiError
    ? cause
    : new ApiError({ code: 'UNEXPECTED_RESPONSE', status: 0 })
}
