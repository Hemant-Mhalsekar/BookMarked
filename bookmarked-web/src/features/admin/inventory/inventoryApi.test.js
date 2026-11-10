import { describe, expect, it } from 'vitest'
import { formatLabel, validateAdjustment } from './inventoryApi'

describe('validateAdjustment', () => {
  it('accepts a restock', () => {
    expect(validateAdjustment({ reason: 'RESTOCK', quantity: '12' }, 3)).toEqual({})
  })
  it('rejects zero, fractions and negative restocks', () => {
    expect(validateAdjustment({ reason: 'RESTOCK', quantity: '0' }, 3).quantity).toBeTruthy()
    expect(validateAdjustment({ reason: 'RESTOCK', quantity: '1.5' }, 3).quantity).toBeTruthy()
    expect(validateAdjustment({ reason: 'RESTOCK', quantity: '-1' }, 3).quantity).toBe('A restock adds copies')
  })
  it('needs a note for corrections and never goes below zero', () => {
    expect(validateAdjustment({ reason: 'ADJUSTMENT', quantity: '-2' }, 3).note).toBeTruthy()
    expect(validateAdjustment({ reason: 'ADJUSTMENT', quantity: '-4', note: 'Lost' }, 3).quantity).toBe('Only 3 in stock')
    expect(validateAdjustment({ reason: 'ADJUSTMENT', quantity: '-3', note: 'Lost' }, 3)).toEqual({})
  })
  it('labels formats', () => {
    expect(formatLabel('PAPERBACK')).toBe('Paperback')
  })
})
