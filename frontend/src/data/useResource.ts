import { useCallback, useEffect, useRef, useState } from 'react'
import type { DependencyList } from 'react'

import { ApiError } from '../api/errors'

import { failed, initial, loaded, started } from './resourceState'
import type { ResourceState } from './resourceState'

/*
 * One way to read something from the API.
 *
 * Every screen in this app needs the same four things — is it loading, did it
 * fail, is it being refreshed, and how do I ask again — and four screens each
 * inventing that is four chances to get the last one wrong.
 *
 * What state follows what lives in resourceState.ts, with no React in it, and
 * is tested there. This file is only the wiring: when to ask, and which answer
 * is still the one being waited for.
 */

export interface Resource<T> extends ResourceState<T> {
  reload: () => void
}

export function useResource<T>(
  load: () => Promise<T>,
  deps: DependencyList,
): Resource<T> {
  /*
   * One state object, not four pieces of state.
   *
   * Four made the next state depend on the current one — whether a request is
   * a first load or a refresh is decided by whether data is already there — and
   * the only way to read the current one inside a setter is an updater
   * function. Updaters have to be pure, and React re-runs them; a setLoading
   * call hidden inside one puts the screen back into loading after it has
   * finished, so a placeholder sits above the data it was waiting for. Held
   * together, every transition is one pure computation.
   */
  const [state, setState] = useState<ResourceState<T>>(initial<T>)

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

    setState(started)

    loadRef.current().then(
      (result) => {
        if (mine !== ticket.current) {
          return
        }
        setState(loaded(result))
      },
      (cause: unknown) => {
        if (mine !== ticket.current) {
          return
        }
        /*
         * A 401 never really surfaces here: the client ends the session, the
         * provider signs out, and this component unmounts before anything can
         * render the error.
         */
        const error = toApiError(cause)
        setState((previous) => failed(previous, error))
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

  return { ...state, reload: run }
}

/** Anything that is not an ApiError escaped the client, which is a bug there —
 *  but a screen still has to render something rather than crash. */
function toApiError(cause: unknown): ApiError {
  return cause instanceof ApiError
    ? cause
    : new ApiError({ code: 'UNEXPECTED_RESPONSE', status: 0 })
}
