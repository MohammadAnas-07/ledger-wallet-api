import type { ApiError } from '../api/errors'

/*
 * The state machine behind useResource, with no React in it.
 *
 * It lives apart from the hook for one reason: the worst bug in this feature
 * was a transition, not a rendering. An earlier version decided "is this a
 * first load or a refresh" inside a state updater by calling another setter —
 * an impure updater, which React is free to re-run, and did, putting the screen
 * back into loading after the load had finished so a placeholder sat above the
 * data it had been waiting for.
 *
 * Nothing here touches a hook, so every one of these transitions can be checked
 * directly, without a DOM and without a renderer. The purity is the point, and
 * it is asserted rather than assumed.
 */

export interface ResourceState<T> {
  /** Null until the first load succeeds. Kept across a failed refresh. */
  data: T | null
  error: ApiError | null
  /** First load, with nothing on screen yet. */
  loading: boolean
  /** Loading again with data already showing. */
  refreshing: boolean
}

export function initial<T>(): ResourceState<T> {
  return { data: null, error: null, loading: true, refreshing: false }
}

/**
 * A request has just been sent.
 *
 * Which kind of wait this is depends entirely on whether there is anything on
 * screen worth preserving — and that is the whole decision, made once, from the
 * previous state alone.
 */
export function started<T>(previous: ResourceState<T>): ResourceState<T> {
  return previous.data === null
    ? { data: null, error: null, loading: true, refreshing: false }
    : { data: previous.data, error: null, loading: false, refreshing: true }
}

export function loaded<T>(data: T): ResourceState<T> {
  return { data, error: null, loading: false, refreshing: false }
}

/**
 * The request failed.
 *
 * Whatever was already on screen stays there. A failed refresh leaving the last
 * known balance visible with an error beside it is more useful than an empty
 * panel, and the screen decides how loudly to say the figure may be stale.
 */
export function failed<T>(
  previous: ResourceState<T>,
  error: ApiError,
): ResourceState<T> {
  return { data: previous.data, error, loading: false, refreshing: false }
}
