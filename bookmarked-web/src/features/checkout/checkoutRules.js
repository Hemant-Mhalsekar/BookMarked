// Mirrors CheckoutService so the page can explain choices up front.
// The server enforces every rule again; these only shape the UI.

export const COD_MAX_ORDER_VALUE = 5000

/** Why Cash on Delivery can't be used for this order, or null if it can. */
export function codBlockReason({ lines, total, emailVerified }) {
  if (lines.some((l) => l.format === 'EBOOK')) return "Cash on Delivery isn't available for e-books."
  if (Number(total) > COD_MAX_ORDER_VALUE) return 'Cash on Delivery is available on orders up to ₹5,000.'
  if (!emailVerified) return 'Verify your email to use Cash on Delivery.'
  return null
}

export const needsAddress = (lines) => lines.some((l) => l.format !== 'EBOOK')

/** ?buy=12&qty=2 → { variantId: 12, quantity: 2 }; anything invalid → null. */
export function parseBuyNow(searchParams) {
  const variantId = Number(searchParams.get('buy'))
  if (!Number.isInteger(variantId) || variantId <= 0) return null
  const qty = Number(searchParams.get('qty') ?? 1)
  const quantity = Number.isInteger(qty) ? Math.min(10, Math.max(1, qty)) : 1
  return { variantId, quantity }
}

export const ORDER_STATUS_TEXT = {
  PENDING_PAYMENT: { label: 'Awaiting payment', tone: 'bg-amber-50 text-amber-800' },
  CONFIRMED: { label: 'Confirmed', tone: 'bg-emerald-50 text-emerald-700' },
  COMPLETED: { label: 'Completed', tone: 'bg-emerald-50 text-emerald-700' },
  CANCELLED: { label: 'Cancelled', tone: 'bg-slate-100 text-slate-600' },
  EXPIRED: { label: 'Payment window closed', tone: 'bg-slate-100 text-slate-600' },
}

export const PAYMENT_STATUS_TEXT = {
  PENDING: 'Payment pending',
  PAID: 'Paid online',
  FAILED: 'Payment failed',
  COD_PENDING: 'Pay on delivery',
  COLLECTED: 'Paid on delivery',
  PARTIALLY_REFUNDED: 'Partly refunded',
  REFUNDED: 'Refunded',
}

export const FULFILLMENT_TEXT = {
  PLACED: 'Order placed',
  PROCESSING: 'Being packed',
  SHIPPED: 'Shipped',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  RETURN_REQUESTED: 'Return requested',
  RETURNED: 'Returned',
}
