import { describe, expect, it } from 'vitest'
import { refundPreview, stepsFor, UPI_REGEX } from './orderRules'

const at = (d) => `2026-11-0${d}T10:00:00Z`

describe('stepsFor', () => {
  it('marks steps done up to the current status, with dates', () => {
    const steps = stepsFor({
      fulfillmentStatus: 'SHIPPED',
      timeline: [{ status: 'PLACED', at: at(1) }, { status: 'PROCESSING', at: at(2) }, { status: 'SHIPPED', at: at(3) }],
    })
    expect(steps.map((s) => [s.status, s.done])).toEqual([
      ['PLACED', true], ['PROCESSING', true], ['SHIPPED', true], ['DELIVERED', false],
    ])
    expect(steps[2].at).toBe(at(3))
    expect(steps[3].at).toBeNull()
  })

  it('stops a cancelled item where it was and adds the cancellation', () => {
    const steps = stepsFor({
      fulfillmentStatus: 'CANCELLED',
      timeline: [{ status: 'PLACED', at: at(1) }, { status: 'CANCELLED', at: at(2), note: 'Cancelled by you' }],
    })
    expect(steps.map((s) => s.status)).toEqual(['PLACED', 'CANCELLED'])
    expect(steps[1].note).toBe('Cancelled by you')
  })
})

describe('refundPreview', () => {
  const order = {
    paymentMethod: 'ONLINE',
    paymentStatus: 'PAID',
    deliveryFee: 40,
    total: 490,
    refunds: [],
    items: [
      { id: 1, format: 'PAPERBACK', lineTotal: 250, fulfillmentStatus: 'PLACED' },
      { id: 2, format: 'PAPERBACK', lineTotal: 200, fulfillmentStatus: 'PROCESSING' },
      { id: 3, format: 'EBOOK', lineTotal: 0, fulfillmentStatus: 'DELIVERED' },
    ],
  }

  it('refunds the items, and delivery only when nothing physical is left', () => {
    expect(refundPreview(order, [1])).toBe(250)
    expect(refundPreview(order, [1, 2])).toBe(490)
  })

  it('never refunds more than is left', () => {
    const partly = { ...order, items: order.items.map((i) => (i.id === 1 ? { ...i, fulfillmentStatus: 'CANCELLED' } : i)), refunds: [{ amount: 250 }] }
    expect(refundPreview(partly, [2])).toBe(240)
  })

  it('is zero for Cash on Delivery or nothing selected', () => {
    expect(refundPreview({ ...order, paymentMethod: 'COD', paymentStatus: 'COD_PENDING' }, [1])).toBe(0)
    expect(refundPreview(order, [])).toBe(0)
  })
})

describe('returns', () => {
  it('adds the return to the end of the stepper', () => {
    const steps = stepsFor({
      fulfillmentStatus: 'RETURN_REQUESTED',
      timeline: ['PLACED', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'RETURN_REQUESTED'].map((status, i) => ({ status, at: at(i + 1) })),
    })
    expect(steps.map((s) => s.status)).toEqual(['PLACED', 'PROCESSING', 'SHIPPED', 'DELIVERED', 'RETURN_REQUESTED'])
    expect(steps.every((s) => s.done)).toBe(true)
  })

  it('checks UPI IDs like the backend', () => {
    expect(UPI_REGEX.test('reader.one@okhdfc')).toBe(true)
    expect(UPI_REGEX.test('9876543210@ybl')).toBe(true)
    expect(UPI_REGEX.test('not a upi')).toBe(false)
    expect(UPI_REGEX.test('name@')).toBe(false)
    expect(UPI_REGEX.test('@okaxis')).toBe(false)
  })
})
