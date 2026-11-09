import { BRAND } from '../../brand'

export const RAZORPAY_SCRIPT = 'https://checkout.razorpay.com/v1/checkout.js'

let loading = null

/** Loads Razorpay Checkout once, on demand (only pages that take payment need it). */
export function loadRazorpay(doc = document) {
  if (typeof window !== 'undefined' && window.Razorpay) return Promise.resolve(window.Razorpay)
  if (!loading) {
    loading = new Promise((resolve, reject) => {
      const script = doc.createElement('script')
      script.src = RAZORPAY_SCRIPT
      script.async = true
      script.onload = () => (window.Razorpay ? resolve(window.Razorpay) : reject(new Error('Razorpay did not load')))
      script.onerror = () => {
        loading = null
        script.remove()
        reject(new Error('Could not load Razorpay. Check your connection and try again.'))
      }
      doc.body.appendChild(script)
    })
  }
  return loading
}

/** Razorpay's success payload (snake_case) → our VerifyRequest (camelCase). */
export function toVerifyRequest(response) {
  return {
    razorpayOrderId: response.razorpay_order_id,
    razorpayPaymentId: response.razorpay_payment_id,
    razorpaySignature: response.razorpay_signature,
  }
}

/** Options for the popup, built only from what the server returned. */
export function checkoutOptions(order) {
  return {
    key: order.keyId,
    amount: order.amountPaise,
    currency: order.currency ?? 'INR',
    order_id: order.razorpayOrderId,
    name: BRAND.name,
    description: `Order ${order.orderNumber}`,
    prefill: order.prefill ?? {},
    notes: { orderNumber: order.orderNumber },
    theme: { color: '#1e3a5f' },
  }
}

/**
 * Opens the popup. Resolves with
 *   { outcome: 'paid', payload }      – Razorpay says paid; still verify on the server
 *   { outcome: 'dismissed' }          – the customer closed it
 *   { outcome: 'failed', reason }     – the payment was declined
 * The server decides whether the order is actually paid.
 */
export async function payWithRazorpay(order) {
  const Razorpay = await loadRazorpay()
  return new Promise((resolve) => {
    let failure = null
    const rzp = new Razorpay({
      ...checkoutOptions(order),
      handler: (response) => resolve({ outcome: 'paid', payload: toVerifyRequest(response) }),
      modal: {
        // Razorpay keeps the popup open after a failure so the customer can try
        // another method; we only report the failure once they close it.
        ondismiss: () => resolve(failure ? { outcome: 'failed', reason: failure } : { outcome: 'dismissed' }),
      },
    })
    rzp.on('payment.failed', (res) => {
      failure = res?.error?.description || 'The payment was declined.'
    })
    rzp.open()
  })
}

/** A fresh key per checkout attempt; reused if the same request is retried. */
export function newIdempotencyKey() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return `k${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`
}
