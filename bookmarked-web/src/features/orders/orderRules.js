// Pure helpers for the order pages (unit-tested in orderRules.test.js).

const STEPS = [
  { status: 'PLACED', label: 'Placed' },
  { status: 'PROCESSING', label: 'Packed' },
  { status: 'SHIPPED', label: 'Shipped' },
  { status: 'DELIVERED', label: 'Delivered' },
]

/**
 * The stepper for one physical item: each step with its date (from the history)
 * and whether it's done. Cancelled items stop where they were and add a final step.
 */
export function stepsFor(item) {
  const when = {}
  for (const t of item.timeline ?? []) {
    if (!when[t.status]) when[t.status] = t.at
  }
  const order = STEPS.map((s) => s.status)
  const reached = order.indexOf(item.fulfillmentStatus)
  const steps = STEPS.map((s, i) => ({
    ...s,
    at: when[s.status] ?? null,
    done: reached >= i || Boolean(when[s.status]),
  }))
  if (item.fulfillmentStatus === 'CANCELLED') {
    const cancelled = (item.timeline ?? []).find((t) => t.status === 'CANCELLED')
    return [
      ...steps.filter((s) => s.done),
      { status: 'CANCELLED', label: 'Cancelled', at: cancelled?.at ?? null, done: true, note: cancelled?.note ?? null },
    ]
  }
  return steps
}

/**
 * What a cancellation would refund, mirroring CancellationService: the items' price,
 * plus delivery once no physical item is left. Nothing for COD or unpaid orders.
 */
export function refundPreview(order, itemIds) {
  const paidOnline = order.paymentMethod === 'ONLINE' && ['PAID', 'PARTIALLY_REFUNDED'].includes(order.paymentStatus)
  if (!paidOnline || itemIds.length === 0) return 0
  const selected = new Set(itemIds)
  let amount = order.items.filter((i) => selected.has(i.id)).reduce((sum, i) => sum + Number(i.lineTotal), 0)
  const physicalLeft = order.items.some(
    (i) => i.format !== 'EBOOK' && i.fulfillmentStatus !== 'CANCELLED' && !selected.has(i.id),
  )
  if (!physicalLeft) amount += Number(order.deliveryFee)
  const alreadyRefunded = (order.refunds ?? []).reduce((sum, r) => sum + Number(r.amount), 0)
  return Math.max(0, Math.min(amount, Number(order.total) - alreadyRefunded))
}

export const CANCEL_REASONS = [
  'Ordered by mistake',
  'Found a better price',
  'Delivery is taking too long',
  'Changed my mind',
]

export const ORDER_TABS = [
  { key: 'ALL', label: 'All' },
  { key: 'IN_PROGRESS', label: 'In progress' },
  { key: 'DELIVERED', label: 'Delivered' },
  { key: 'CANCELLED', label: 'Cancelled' },
]

export const REFUND_STATUS_TEXT = {
  PENDING: 'Refund initiated',
  PROCESSED: 'Refunded',
  FAILED: 'Refund delayed',
}
