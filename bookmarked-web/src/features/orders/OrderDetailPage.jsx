import { useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { BRAND } from '../../brand'
import BookCover from '../../components/BookCover'
import { Alert, FullPageSpinner, Spinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { FORMAT_LABELS, formatDateTime, formatPrice } from '../../utils/format'
import { useOrderQuery, useRetryPaymentMutation, useVerifyPaymentMutation } from '../checkout/checkoutApi'
import { FULFILLMENT_TEXT, ORDER_STATUS_TEXT, PAYMENT_STATUS_TEXT } from '../checkout/checkoutRules'
import { payWithRazorpay } from '../checkout/razorpay'

export function StatusBadge({ status }) {
  const s = ORDER_STATUS_TEXT[status] ?? { label: status, tone: 'bg-slate-100 text-slate-600' }
  return <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${s.tone}`}>{s.label}</span>
}

/** `success` shows the thank-you header used right after placing an order. */
export default function OrderDetailPage({ success = false }) {
  const { orderNumber } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const { data: order, isLoading, error, refetch } = useOrderQuery(orderNumber)
  const [retryPayment] = useRetryPaymentMutation()
  const [verifyPayment] = useVerifyPaymentMutation()
  const [paying, setPaying] = useState(false)
  const [note, setNote] = useState(location.state?.paymentNote ?? null)

  if (isLoading) return <FullPageSpinner />
  if (error) {
    return (
      <div className="mx-auto max-w-xl px-4 py-20 text-center">
        <h1 className="text-2xl font-bold">Order not found</h1>
        <p className="mt-2 text-slate-600">{parseApiError(error).message}</p>
        <Link to="/orders" className="btn-primary mt-6 inline-flex">My orders</Link>
      </div>
    )
  }

  const completePayment = async () => {
    setNote(null)
    setPaying(true)
    try {
      const fresh = await retryPayment(order.orderNumber).unwrap()
      const result = await payWithRazorpay(fresh)
      if (result.outcome === 'paid') {
        await verifyPayment(result.payload).unwrap()
        navigate(`/order-success/${order.orderNumber}`, { replace: true })
        return
      }
      setNote(result.outcome === 'failed' ? result.reason : 'Payment was not completed.')
    } catch (e) {
      setNote(e instanceof Error ? e.message : parseApiError(e).message)
      refetch()
    } finally {
      setPaying(false)
    }
  }

  const hasEbooks = order.items.some((i) => i.format === 'EBOOK')
  const hasPhysical = order.items.some((i) => i.format !== 'EBOOK')

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      {success && order.status === 'CONFIRMED' && (
        <div className="mb-6 rounded-xl border border-emerald-200 bg-emerald-50 p-6 text-center">
          <p className="text-3xl" aria-hidden="true">✓</p>
          <h1 className="mt-1 text-2xl font-bold text-emerald-900">Thank you! Your order is confirmed.</h1>
          <p className="mt-1 text-sm text-emerald-800">
            {hasPhysical && 'We’ll email you when it ships. '}
            {hasEbooks && `Your e-books are ready in ${BRAND.library}.`}
          </p>
        </div>
      )}

      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          {!success && <Link to="/orders" className="link text-sm">← My orders</Link>}
          <h2 className="mt-1 text-xl font-bold">Order {order.orderNumber}</h2>
          <p className="text-sm text-slate-500">Placed {formatDateTime(order.createdAt)}</p>
        </div>
        <StatusBadge status={order.status} />
      </div>

      <div className="mt-4 space-y-3">
        {note && <Alert type="warning">{note}</Alert>}
        {order.awaitingPayment && (
          <Alert type="info">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <span>
                We&apos;re holding these books until {formatDateTime(order.reservedUntil)}. Complete the payment to confirm the order.
              </span>
              <button type="button" className="btn-primary" disabled={paying} onClick={completePayment}>
                {paying && <Spinner className="h-4 w-4" />}
                Complete payment
              </button>
            </div>
          </Alert>
        )}
        {order.status === 'EXPIRED' && (
          <Alert type="warning">The payment window closed, so the books were released. You weren&apos;t charged.</Alert>
        )}
        {order.paymentStatus === 'REFUNDED' && (
          <Alert type="info">Your payment was refunded. It can take 5–7 working days to show in your account.</Alert>
        )}
      </div>

      <div className="mt-6 grid grid-cols-1 gap-6 md:grid-cols-[1fr_280px]">
        <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white">
          {order.items.map((item) => (
            <li key={item.id} className="flex gap-3 p-4">
              <Link to={`/book/${item.slug}`} className="w-14 shrink-0">
                <BookCover title={item.title} src={item.coverImageUrl} compact />
              </Link>
              <div className="min-w-0 flex-1 text-sm">
                <Link to={`/book/${item.slug}`} className="font-medium hover:text-brand-700">{item.title}</Link>
                <p className="text-slate-500">{FORMAT_LABELS[item.format]} · Qty {item.quantity} · {formatPrice(item.unitPrice)} each</p>
                {order.status !== 'PENDING_PAYMENT' && order.status !== 'EXPIRED' && (
                  <p className="mt-1 text-xs font-medium text-brand-800">
                    {item.format === 'EBOOK' && item.fulfillmentStatus === 'DELIVERED'
                      ? `In ${BRAND.library}`
                      : FULFILLMENT_TEXT[item.fulfillmentStatus] ?? item.fulfillmentStatus}
                  </p>
                )}
              </div>
              <div className="text-sm font-semibold">{formatPrice(item.lineTotal)}</div>
            </li>
          ))}
        </ul>

        <aside className="h-fit space-y-4">
          <div className="rounded-xl border border-slate-200 bg-white p-4 text-sm">
            <h3 className="font-semibold">Payment</h3>
            <p className="mt-1 text-slate-600">
              {order.paymentMethod === 'COD' ? 'Cash on Delivery' : 'Online (Razorpay)'} · {PAYMENT_STATUS_TEXT[order.paymentStatus] ?? order.paymentStatus}
            </p>
            <dl className="mt-3 space-y-1.5">
              <Row label="Subtotal" value={formatPrice(order.subtotal)} />
              {Number(order.discount) > 0 && <Row label="You saved" value={formatPrice(order.discount)} />}
              <Row label="Delivery" value={Number(order.deliveryFee) > 0 ? formatPrice(order.deliveryFee) : 'FREE'} />
              {Number(order.codFee) > 0 && <Row label="COD fee" value={formatPrice(order.codFee)} />}
              <div className="flex justify-between border-t border-slate-200 pt-2 font-semibold">
                <dt>Total</dt>
                <dd>{formatPrice(order.total)}</dd>
              </div>
            </dl>
          </div>
          {order.shipping && (
            <div className="rounded-xl border border-slate-200 bg-white p-4 text-sm">
              <h3 className="font-semibold">Delivering to</h3>
              <p className="mt-1 font-medium">{order.shipping.name}</p>
              <p className="text-slate-600">
                {[order.shipping.line1, order.shipping.line2, order.shipping.city, order.shipping.state].filter(Boolean).join(', ')} – {order.shipping.pincode}
              </p>
              <p className="text-slate-500">{order.shipping.phone}</p>
            </div>
          )}
          {success && (
            <div className="flex flex-col gap-2">
              <Link to="/orders" className="btn-secondary">View all orders</Link>
              <Link to="/books" className="btn-primary">Keep browsing</Link>
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}

function Row({ label, value }) {
  return (
    <div className="flex justify-between">
      <dt className="text-slate-600">{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}
