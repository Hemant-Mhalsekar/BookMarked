import { describe, expect, it } from 'vitest'
import { codBlockReason, needsAddress, parseBuyNow } from './checkoutRules'

const book = { format: 'PAPERBACK' }
const ebook = { format: 'EBOOK' }

describe('codBlockReason', () => {
  it('allows physical orders up to ₹5,000 for verified emails', () => {
    expect(codBlockReason({ lines: [book], total: 5000, emailVerified: true })).toBeNull()
  })
  it('blocks e-books, large orders and unverified emails', () => {
    expect(codBlockReason({ lines: [book, ebook], total: 500, emailVerified: true })).toMatch(/e-books/)
    expect(codBlockReason({ lines: [book], total: 5000.01, emailVerified: true })).toMatch(/5,000/)
    expect(codBlockReason({ lines: [book], total: 500, emailVerified: false })).toMatch(/Verify/)
  })
})

describe('needsAddress', () => {
  it('only for physical books', () => {
    expect(needsAddress([ebook])).toBe(false)
    expect(needsAddress([ebook, { format: 'HARDCOVER' }])).toBe(true)
  })
})

describe('parseBuyNow', () => {
  const p = (q) => parseBuyNow(new URLSearchParams(q))
  it('reads the variant and clamps the quantity', () => {
    expect(p('buy=12&qty=3')).toEqual({ variantId: 12, quantity: 3 })
    expect(p('buy=12')).toEqual({ variantId: 12, quantity: 1 })
    expect(p('buy=12&qty=99')).toEqual({ variantId: 12, quantity: 10 })
    expect(p('buy=12&qty=0')).toEqual({ variantId: 12, quantity: 1 })
    expect(p('buy=12&qty=abc')).toEqual({ variantId: 12, quantity: 1 })
  })
  it('ignores anything that is not a positive id', () => {
    expect(p('')).toBeNull()
    expect(p('buy=-1')).toBeNull()
    expect(p('buy=1.5')).toBeNull()
    expect(p('buy=<script>')).toBeNull()
  })
})
