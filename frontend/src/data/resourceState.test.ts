import { describe, expect, it } from 'vitest'

import { ApiError } from '../api/errors'

import { failed, initial, loaded, started } from './resourceState'

const boom = new ApiError({ code: 'NETWORK_ERROR', status: 0 })

describe('initial', () => {
  it('waits with nothing to show', () => {
    expect(initial<string>()).toEqual({
      data: null,
      error: null,
      loading: true,
      refreshing: false,
    })
  })
})

describe('started', () => {
  it('is a first load when there is nothing on screen', () => {
    expect(started(initial<string>())).toEqual({
      data: null,
      error: null,
      loading: true,
      refreshing: false,
    })
  })

  it('is a refresh when there is, and keeps what is showing', () => {
    expect(started(loaded('1,250.00'))).toEqual({
      data: '1,250.00',
      error: null,
      loading: false,
      refreshing: true,
    })
  })

  it('never puts the screen back into loading once data has arrived', () => {
    /*
     * This is the regression. The bug it guards was not a wrong value but a
     * wrong *re-entry*: loading became true again after a load had finished, so
     * a placeholder rendered above the data it had been waiting for. Any future
     * version of `started` that sets loading while data exists fails here.
     */
    const withData = loaded('1,250.00')
    expect(started(withData).loading).toBe(false)
    expect(started(started(withData)).loading).toBe(false)
  })

  it('clears a previous error, because a new attempt is not the old failure', () => {
    expect(started(failed(loaded('10.00'), boom)).error).toBeNull()
  })

  it('is pure: repeating it gives the same answer and changes nothing', () => {
    /*
     * The property the original bug violated. React may run a state updater
     * more than once, and is entitled to — so calling this twice must be
     * indistinguishable from calling it once, and it must not touch its input.
     */
    const before = loaded('99.99')
    const snapshot = { ...before }

    expect(started(before)).toEqual(started(before))
    expect(before).toEqual(snapshot)
  })
})

describe('loaded', () => {
  it('ends both kinds of wait and clears any error', () => {
    expect(loaded('42.00')).toEqual({
      data: '42.00',
      error: null,
      loading: false,
      refreshing: false,
    })
  })
})

describe('failed', () => {
  it('keeps data that is already on screen', () => {
    expect(failed(loaded('1,250.00'), boom)).toEqual({
      data: '1,250.00',
      error: boom,
      loading: false,
      refreshing: false,
    })
  })

  it('has nothing to keep when the first load is the one that failed', () => {
    expect(failed(started(initial<string>()), boom)).toEqual({
      data: null,
      error: boom,
      loading: false,
      refreshing: false,
    })
  })

  it('ends the wait, so a failure never leaves a placeholder up', () => {
    const during = started(initial<string>())
    expect(during.loading).toBe(true)
    expect(failed(during, boom).loading).toBe(false)
    expect(failed(during, boom).refreshing).toBe(false)
  })
})

describe('a whole life', () => {
  it('load, refresh, fail, recover', () => {
    let state = initial<string>()
    expect(state.loading).toBe(true)

    state = loaded('100.00')
    expect(state).toEqual({
      data: '100.00',
      error: null,
      loading: false,
      refreshing: false,
    })

    state = started(state)
    expect(state.refreshing).toBe(true)
    expect(state.data).toBe('100.00')

    state = failed(state, boom)
    // The figure survives the failed refresh — that is the point of keeping it.
    expect(state.data).toBe('100.00')
    expect(state.error).toBe(boom)

    state = started(state)
    expect(state.error).toBeNull()

    state = loaded('150.00')
    expect(state).toEqual({
      data: '150.00',
      error: null,
      loading: false,
      refreshing: false,
    })
  })
})
