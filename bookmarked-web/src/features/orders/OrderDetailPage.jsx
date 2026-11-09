import { useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { BRAND } from '../../brand'
import BookCover from '../../components/BookCover'
import { Alert, FullPageSpinner, Spinner } from '../../components/ui'
import { saveObjectUrl } from '../../utils/download'
import { parseApiError } from '../../utils/errors'
import { FORMAT_LABELS, formatDateTime, formatPrice } from '../../utils/format'
import {
  useCancelOrderMutation,
  useLazyInvoiceQuery,
  useOrderQuery,
  useRequestReturnMutation,
  useRetryPaymentMutation,
  useVerifyPaymentMutation,
} from '../checkout/checkoutApi'
import { ORDER_STATUS_TEXT, PAYMENT_STATUS_TEXT } from '../checkout/checkoutRules'
import { payWithRazorpay } from '../checkout/razorpay'
import {
  CANCEL_REASONS,
  REFUND_STATUS_TEXT,
  refundPreview,
  RETURN_REASON_TEXT,
  RETURN_REASONS,
  RETURN_STATUS_TEXT,
  stepsFor,
  UPI_REGEX,
} from './orderRules'

export function StatusBadge({ status }) {
  const s = ORDER_STATUS_TEXT[status] ?? { label: status, tone: 'bg-slate-100 text-slate-600' }
  return <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${s.tone}`}>{s.label}</span>
}

const shortDate = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short' })
const fmtShort = (iso) => (iso ? shortDate.format(new Date(iso)) : '')

/** `success` shows the thank-you header used right after placing an order. */
export default function OrderDetailPage({ success = false }) {
  const { orderNumber } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const { data: order, isLoading, error, refetch } = useOrderQuery(orderNumber)
  const [retryPayment] = useRetryPaymentMutation()
  const [verifyPayment] = useVerifyPaymentMutation()
  const [fetchInvoice, invoiceState] = useLazyInvoiceQuery()
  const [paying, setPaying] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [note, setNote] = useState(location.state?.paymentNote ?? null)
  const [notice, setNotice] = useState(null)

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

  const downloadInvoice = async () => {
    setNote(null)
    try {
      const url = await fetchInvoice(order.orderNumber).unwrap()
      saveObjectUrl(url, `BookMarked-Invoice-${order.orderNumber}.pdf`)
    } catch (e) {
      setNote(parseApiError(e).message)
    }
  }

  const hasEbooks = order.items.some((i) => i.format === 'EBOOK')
  const hasPhysical = order.items.some((i) => i.format !== 'EBOOK')
  const placed = !['PENDING_PAYMENT', 'EXPIRED'].includes(order.status)
  const confirmed = order.status === 'CONFIRMED' || order.status === 'COMPLETED'

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      {success && confirmed && (
        <div className="mb-6 rounded-xl border border-emerald-200 bg-emerald-50 p-6 text-center">
          <p className="text-3xl text-emerald-700" aria-hidden="true">✓</p>
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
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge status={order.status} />
          {order.invoiceAvailable && (
            <button type="button" className="btn-secondary py-1.5 text-sm" onClick={downloadInvoice} disabled={invoiceState.isFetching}>
              {invoiceState.isFetching && <Spinner className="h-4 w-4" />}
              Invoice (PDF)
            </button>
          )}
          {order.cancellable && !cancelling && (
            <button type="button" className="btn-secondary py-1.5 text-sm text-red-700" onClick={() => setCancelling(true)}>
              Cancel items
            </button>
          )}
        </div>
      </div>

      <div className="mt-4 space-y-3">
        {note && <Alert type="warning">{note}</Alert>}
        {notice && <Alert type="success">{notice}</Alert>}
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
      </div>

      {cancelling && (
        <CancelPanel
          order={order}
          onClose={() => setCancelling(false)}
          onDone={(msg) => {
            setCancelling(false)
            setNotice(msg)
          }}
        />
      )}

      <div className="mt-6 grid grid-cols-1 gap-6 md:grid-cols-[1fr_280px]">
        <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white">
          {order.items.map((item) => (
            <li key={item.id} className="p-4">
              <div className="flex gap-3">
                <Link to={`/book/${item.slug}`} className="w-14 shrink-0">
                  <BookCover title={item.title} src={item.coverImageUrl} compact />
                </Link>
                <div className="min-w-0 flex-1 text-sm">
                  <Link to={`/book/${item.slug}`} className="font-medium hover:text-brand-700">{item.title}</Link>
                  <p className="text-slate-500">{FORMAT_LABELS[item.format]} · Qty {item.quantity} · {formatPrice(item.unitPrice)} each</p>
                  {placed && item.format === 'EBOOK' && item.fulfillmentStatus === 'DELIVERED' && (
                    <p className="mt-1 text-xs font-medium text-emerald-700">
                      Delivered · <Link to={`/library/read/${item.variantId}`} className="underline">Read now</Link>
                    </p>
                  )}
                </div>
                <div className="text-sm font-semibold">{formatPrice(item.lineTotal)}</div>
              </div>
              {placed && item.format !== 'EBOOK' && <ItemTimeline item={item} />}
              {(item.returnable || item.returnRequest) && <ReturnSection order={order} item={item} />}
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
              <Row label="Items (MRP)" value={formatPrice(order.subtotal)} />
              {Number(order.discount) > 0 && <Row label="You saved" value={`−${formatPrice(order.discount)}`} />}
              <Row label="Delivery" value={Number(order.deliveryFee) > 0 ? formatPrice(order.deliveryFee) : 'FREE'} />
              {Number(order.codFee) > 0 && <Row label="COD fee" value={formatPrice(order.codFee)} />}
              <div className="flex justify-between border-t border-slate-200 pt-2 font-semibold">
                <dt>Total</dt>
                <dd>{formatPrice(order.total)}</dd>
              </div>
              {order.refunds?.map((r, i) => (
                <div key={i} className="flex justify-between text-emerald-700">
                  <dt>{REFUND_STATUS_TEXT[r.status] ?? r.status} · {fmtShort(r.createdAt)}</dt>
                  <dd>−{formatPrice(r.amount)}</dd>
                </div>
              ))}
            </dl>
            {order.refunds?.length > 0 && (
              <p className="mt-2 text-xs text-slate-500">Refunds reach your original payment method in 5–7 working days.</p>
            )}
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

function ItemTimeline({ item }) {
  const steps = stepsFor(item)
  return (
    <div className="mt-3 sm:pl-[68px]">
      <ol className="flex flex-col items-start gap-1 text-xs sm:flex-row sm:flex-wrap sm:items-center sm:gap-x-0 sm:gap-y-1" aria-label={`Progress of ${item.title}`}>
        {steps.map((s, i) => (
          <li key={s.status} className="flex items-center">
            {i > 0 && <span className={`hidden h-px w-3 sm:block ${s.done ? 'bg-brand-700' : 'bg-slate-300'}`} aria-hidden="true" />}
            <span className={`flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 ${
              s.status === 'CANCELLED' ? 'bg-slate-100 text-slate-600'
                : s.done ? 'bg-brand-50 font-medium text-brand-800' : 'text-slate-400'}`}>
              <span className={`h-1.5 w-1.5 rounded-full ${s.status === 'CANCELLED' ? 'bg-slate-500' : s.done ? 'bg-brand-700' : 'bg-slate-300'}`} aria-hidden="true" />
              {s.label}
              {s.at && <span className="font-normal text-slate-500">{fmtShort(s.at)}</span>}
            </span>
          </li>
        ))}
      </ol>
      {item.shipment && item.fulfillmentStatus !== 'CANCELLED' && (
        <p className="mt-2 text-xs text-slate-600">
          {item.shipment.courierName} · Tracking <span className="font-mono font-medium">{item.shipment.trackingNumber}</span>
          {item.fulfillmentStatus === 'SHIPPED' && item.shipment.expectedDelivery && (
            <> · Expected by {fmtShort(item.shipment.expectedDelivery)}</>
          )}
        </p>
      )}
      {item.fulfillmentStatus === 'CANCELLED' && steps.at(-1)?.note && (
        <p className="mt-2 text-xs text-slate-500">{steps.at(-1).note}</p>
      )}
    </div>
  )
}

/** "Return" button and form for a delivered book, or the status of its return. */
function ReturnSection({ order, item }) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  const [comment, setComment] = useState('')
  const [upi, setUpi] = useState('')
  const [errors, setErrors] = useState({})
  const [error, setError] = useState(null)
  const [requestReturn, { isLoading }] = useRequestReturnMutation()
  const cod = order.paymentMethod === 'COD'
  const r = item.returnRequest

  if (r) {
    const declined = r.status === 'REJECTED'
    return (
      <div className={`mt-3 rounded-lg px-3 py-2 text-xs sm:ml-[68px] ${declined ? 'bg-slate-100 text-slate-700' : 'bg-amber-50 text-amber-900'}`}>
        <p className="font-medium">{RETURN_STATUS_TEXT[r.status] ?? r.status}</p>
        <p>
          {RETURN_REASON_TEXT[r.reason]} · refund {formatPrice(r.refundAmount)}
          {r.status !== 'REFUNDED' && !declined && (cod ? ' by UPI' : ' to your original payment method')}
        </p>
        {r.adminNote && <p className="mt-1">Note from BookMarked: {r.adminNote}</p>}
      </div>
    )
  }

  if (!open) {
    return (
      <div className="mt-2 flex flex-wrap items-center gap-2 text-xs sm:ml-[68px]">
        <button type="button" className="btn-secondary py-1 text-xs" onClick={() => setOpen(true)}>Return this book</button>
        <span className="text-slate-500">Returnable until {formatDateTime(item.returnBy)}</span>
      </div>
    )
  }

  const submit = async (e) => {
    e.preventDefault()
    setError(null)
    const found = {}
    if (!reason) found.reason = 'Choose a reason'
    if (cod && !UPI_REGEX.test(upi.trim())) found.refundUpiId = 'Enter a UPI ID like name@okbank'
    if (comment.length > 1000) found.comment = 'Keep it under 1000 characters'
    setErrors(found)
    if (Object.keys(found).length) return
    try {
      await requestReturn({
        orderNumber: order.orderNumber, itemId: item.id, reason,
        comment: comment.trim() || null, refundUpiId: cod ? upi.trim() : null,
      }).unwrap()
    } catch (err) {
      const parsed = parseApiError(err)
      setErrors(parsed.fieldErrors)
      setError(parsed.message)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="mt-3 space-y-3 rounded-lg border border-slate-200 bg-slate-50 p-3 text-sm sm:ml-[68px]">
      <p className="font-medium">Return {item.title}</p>
      <label className="block">
        <span className="text-slate-700">Why are you returning it?</span>
        <select className={`input mt-1 ${errors.reason ? 'input-error' : ''}`} value={reason} onChange={(e) => setReason(e.target.value)}>
          <option value="">Choose a reason</option>
          {RETURN_REASONS.map((x) => <option key={x.value} value={x.value}>{x.label}</option>)}
        </select>
        {errors.reason && <span className="text-xs text-red-600">{errors.reason}</span>}
      </label>
      <label className="block">
        <span className="text-slate-700">Anything we should know? (optional)</span>
        <textarea className="input mt-1" rows={2} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)} />
      </label>
      {cod && (
        <label className="block">
          <span className="text-slate-700">UPI ID for your refund</span>
          <input className={`input mt-1 ${errors.refundUpiId ? 'input-error' : ''}`} placeholder="name@okbank" value={upi}
                 onChange={(e) => setUpi(e.target.value)} autoComplete="off" />
          <span className={`text-xs ${errors.refundUpiId ? 'text-red-600' : 'text-slate-500'}`}>
            {errors.refundUpiId ?? 'You paid cash, so we’ll send the refund to this UPI ID.'}
          </span>
        </label>
      )}
      <p className="text-xs text-slate-500">
        Refund: {formatPrice(item.lineTotal)} once the book reaches us. Returns are refund only; delivery charges aren&apos;t refunded.
      </p>
      {error && <Alert type="error">{error}</Alert>}
      <div className="flex gap-2">
        <button type="submit" className="btn-primary py-1.5 text-sm" disabled={isLoading}>
          {isLoading && <Spinner className="h-4 w-4" />}
          Request return
        </button>
        <button type="button" className="btn-secondary py-1.5 text-sm" onClick={() => setOpen(false)}>Not now</button>
      </div>
    </form>
  )
}

function CancelPanel({ order, onClose, onDone }) {
  const cancellable = order.items.filter((i) => i.cancellable)
  const [selected, setSelected] = useState(() => cancellable.map((i) => i.id))
  const [reason, setReason] = useState(CANCEL_REASONS[0])
  const [error, setError] = useState(null)
  const [cancelOrder, { isLoading }] = useCancelOrderMutation()
  const refund = refundPreview(order, selected)
  const everything = selected.length === cancellable.length

  const toggle = (id) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]))

  const submit = async () => {
    setError(null)
    try {
      await cancelOrder({ orderNumber: order.orderNumber, itemIds: everything ? null : selected, reason }).unwrap()
      onDone(refund > 0
        ? `Cancelled. ${formatPrice(refund)} will be refunded to your original payment method.`
        : 'Cancelled. You won’t be charged for these books.')
    } catch (e) {
      setError(parseApiError(e).message)
    }
  }

  return (
    <section className="mt-6 rounded-xl border border-red-200 bg-white p-5" aria-labelledby="cancel-title">
      <h3 id="cancel-title" className="font-semibold">Cancel items</h3>
      <p className="text-sm text-slate-500">Books that haven&apos;t shipped yet can be cancelled.</p>
      <fieldset className="mt-3 space-y-2">
        <legend className="sr-only">Items to cancel</legend>
        {cancellable.map((i) => (
          <label key={i.id} className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={selected.includes(i.id)} onChange={() => toggle(i.id)} />
            <span className="flex-1">{i.title} · {FORMAT_LABELS[i.format]} × {i.quantity}</span>
            <span className="text-slate-600">{formatPrice(i.lineTotal)}</span>
          </label>
        ))}
      </fieldset>
      <label className="mt-4 block text-sm">
        <span className="font-medium text-slate-700">Reason</span>
        <select className="input mt-1" value={reason} onChange={(e) => setReason(e.target.value)}>
          {CANCEL_REASONS.map((r) => <option key={r}>{r}</option>)}
        </select>
      </label>
      <p className="mt-3 text-sm">
        {refund > 0
          ? <>Refund: <strong>{formatPrice(refund)}</strong> to your original payment method (5–7 working days).</>
          : order.paymentMethod === 'COD' ? 'Nothing to refund: this is Cash on Delivery.' : null}
      </p>
      {error && <div className="mt-3"><Alert type="error">{error}</Alert></div>}
      <div className="mt-4 flex gap-3">
        <button type="button" className="btn border border-red-700 bg-red-700 text-white hover:bg-red-800"
                disabled={isLoading || selected.length === 0} onClick={submit}>
          {isLoading && <Spinner className="h-4 w-4" />}
          {everything ? 'Cancel all' : `Cancel ${selected.length} ${selected.length === 1 ? 'item' : 'items'}`}
        </button>
        <button type="button" className="btn-secondary" onClick={onClose} disabled={isLoading}>Keep my order</button>
      </div>
    </section>
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
