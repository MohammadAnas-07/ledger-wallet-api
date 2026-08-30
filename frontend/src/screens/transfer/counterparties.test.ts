import { describe, expect, it } from 'vitest'

import type { AccountResponse, StatementEntryResponse } from '../../api/types'

import { counterpartiesFrom } from './counterparties'

function own(id: string, accountNumber = `ACC-${id}`): AccountResponse {
  return {
    id,
    accountNumber,
    balance: '0.00',
    status: 'ACTIVE',
    createdAt: '2026-08-30T09:00:00Z',
  }
}

function entry(
  counterparty: { accountId: string; accountNumber: string } | null,
  overrides: Partial<StatementEntryResponse> = {},
): StatementEntryResponse {
  return {
    entryId: `entry-${Math.random()}`,
    transactionId: 'txn',
    type: counterparty === null ? 'DEPOSIT' : 'TRANSFER',
    direction: 'CREDIT',
    amount: '10.00',
    balanceAfter: '10.00',
    counterparty,
    createdAt: '2026-08-30T09:00:00Z',
    ...overrides,
  }
}

const alice = { accountId: 'alice-id', accountNumber: 'ACC-ALICE' }
const bob = { accountId: 'bob-id', accountNumber: 'ACC-BOB' }

describe('counterpartiesFrom', () => {
  it('finds the accounts on the other side of transfers', () => {
    expect(counterpartiesFrom([entry(alice), entry(bob)], [])).toEqual([
      alice,
      bob,
    ])
  })

  it('ignores deposits and withdrawals', () => {
    // Their counterparty is the system account, which the API withholds and
    // nobody can send to.
    expect(counterpartiesFrom([entry(null), entry(alice), entry(null)], [])).toEqual(
      [alice],
    )
  })

  it('lists each account once, however many times it appears', () => {
    expect(
      counterpartiesFrom([entry(alice), entry(alice), entry(alice)], []),
    ).toEqual([alice])
  })

  it('leaves out the sender own wallets', () => {
    // They are offered under their own heading, with a live balance beside
    // them. Twice in one dropdown is twice to think about.
    const mine = own('alice-id', 'ACC-ALICE')
    expect(counterpartiesFrom([entry(alice), entry(bob)], [mine])).toEqual([bob])
  })

  it('keeps statement order, which is most recently dealt with first', () => {
    // The backend sorts createdAt DESC, so the first entry is the newest.
    expect(counterpartiesFrom([entry(bob), entry(alice)], [])).toEqual([bob, alice])
  })

  it('has nothing to offer from an empty statement', () => {
    expect(counterpartiesFrom([], [own('mine')])).toEqual([])
  })
})
