import { describe, expect, it } from 'vitest'
import { distributionPercents, validateReview } from './reviewsApi'

describe('validateReview', () => {
  it('accepts a normal review', () => {
    expect(validateReview({ rating: 4, title: 'Good', comment: 'Clear and practical throughout.' })).toEqual({})
  })
  it('flags missing stars, long titles and short comments (ignoring HTML)', () => {
    const e = validateReview({ rating: 0, title: 'x'.repeat(101), comment: '<b>tiny</b>' })
    expect(Object.keys(e).sort()).toEqual(['comment', 'rating', 'title'])
  })
})

describe('distributionPercents', () => {
  it('turns counts into bar widths from 5 stars down', () => {
    expect(distributionPercents({ 5: 3, 4: 1, 3: 0, 2: 0, 1: 0 })).toEqual([
      { stars: 5, count: 3, percent: 75 },
      { stars: 4, count: 1, percent: 25 },
      { stars: 3, count: 0, percent: 0 },
      { stars: 2, count: 0, percent: 0 },
      { stars: 1, count: 0, percent: 0 },
    ])
    expect(distributionPercents({})[0].percent).toBe(0)
  })
})
