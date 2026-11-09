import { afterEach, describe, expect, it, vi } from 'vitest'
import { checkoutOptions, newIdempotencyKey, payWithRazorpay, toVerifyRequest } from './razorpay'

const order = {
  orderNumber: 'BM-20261109-000042',
  keyId: 'rzp_test_abc',
  amountPaise: 119800,
  currency: 'INR',
  razorpayOrderId: 'order_X1',
  prefill: { name: 'Asha', email: 'asha@example.com', contact: '9876543210' },
}

/** A stand-in for window.Razorpay that lets each test drive the popup. */
function fakeRazorpay(behaviour) {
  return vi.fn(function Razorpay(options) {
    const handlers = {}
    this.on = (event, fn) => {
      handlers[event] = fn
    }
    this.open = () => behaviour(options, handlers)
  })
}

afterEach(() => {
  delete window.Razorpay
})

describe('razorpay helpers', () => {
  it('builds options only from the server response', () => {
    const o = checkoutOptions(order)
    expect(o).toMatchObject({ key: 'rzp_test_abc', amount: 119800, currency: 'INR', order_id: 'order_X1', name: 'BookMarked' })
    expect(o.prefill.email).toBe('asha@example.com')
    expect(o).not.toHaveProperty('key_secret')
  })

  it('maps the success payload to camelCase', () => {
    expect(toVerifyRequest({ razorpay_order_id: 'o', razorpay_payment_id: 'p', razorpay_signature: 's' }))
      .toEqual({ razorpayOrderId: 'o', razorpayPaymentId: 'p', razorpaySignature: 's' })
  })

  it('makes keys the backend accepts', () => {
    const key = newIdempotencyKey()
    expect(key).toMatch(/^[A-Za-z0-9_-]{8,40}$/)
    expect(newIdempotencyKey()).not.toBe(key)
  })
})

describe('payWithRazorpay', () => {
  it('resolves paid with the verify payload', async () => {
    window.Razorpay = fakeRazorpay((opts) =>
      opts.handler({ razorpay_order_id: 'order_X1', razorpay_payment_id: 'pay_1', razorpay_signature: 'sig' }))
    await expect(payWithRazorpay(order)).resolves.toEqual({
      outcome: 'paid',
      payload: { razorpayOrderId: 'order_X1', razorpayPaymentId: 'pay_1', razorpaySignature: 'sig' },
    })
  })

  it('reports a dismissed popup', async () => {
    window.Razorpay = fakeRazorpay((opts) => opts.modal.ondismiss())
    await expect(payWithRazorpay(order)).resolves.toEqual({ outcome: 'dismissed' })
  })

  it('reports a declined payment once the popup closes', async () => {
    window.Razorpay = fakeRazorpay((opts, handlers) => {
      handlers['payment.failed']({ error: { description: 'Card declined' } })
      opts.modal.ondismiss()
    })
    await expect(payWithRazorpay(order)).resolves.toEqual({ outcome: 'failed', reason: 'Card declined' })
  })
})
