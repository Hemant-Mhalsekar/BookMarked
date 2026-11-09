import { useMemo, useState } from 'react'
import { useSelector } from 'react-redux'
import { Link, useParams } from 'react-router-dom'
import { Alert, FullPageSpinner, Spinner, TextInput } from '../../../components/ui'
import { saveObjectUrl } from '../../../utils/download'
import { parseApiError } from '../../../utils/errors'
import { FORMAT_LABELS, formatDateTime, formatPrice } from '../../../utils/format'
import { selectUser } from '../../auth/authSlice'
import { StatusBadge } from '../../orders/OrderDetailPage'
import {
  ITEM_STATUS_TEXT,
  ITEM_STATUS_TONE,
  NEXT_STEP,
  useAdminCancelMutation,
  useAdminOrderQuery,
  useCodCollectedMutation,
  useDeliverItemsMutation,
  useLazyAdminInvoiceQuery,
  useProcessItemsMutation,
  useShipItemsMutation,
} from './adminOrdersApi'

const todayPlus = (days) => {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

export default function AdminOrderPage() {
  const { orderNumber } = useParams()
  const user = useSelector(selectUser)
  const canUpdate = user.permissions.includes('ORDER_UPDATE')
  const canRefund = user.permissions.includes('REFUND_ISSUE')
  const { data: order, isLoading, error } = useAdminOrderQuery(orderNumber)
  const [message, setMessage] = useState(null)
  const [fetchInvoice] = useLazyAdminInvoiceQuery()

  if (isLoading) return <FullPageSpinner />
  if (error) return <Alert type="error">{parseApiError(error).message}</Alert>

  const physical = order.items.filter((i) => i.format !== 'EBOOK')
  const groups = ['PLACED', 'PROCESSING', 'SHIPPED']
    .map((status) => ({ status, items: physical.filter((i) => i.fulfillmentStatus === status) }))
    .filter((g) => g.items.length > 0)
  const done = order.items.filter((i) => !groups.some((g) => g.items.includes(i)))
  const open = order.status === 'CONFIRMED'
  const cancellable = physical.filter((i) => ['PLACED', 'PROCESSING'].includes(i.fulfillmentStatus))
  const codReady = order.paymentMethod === 'COD' && order.paymentStatus === 'COD_PENDING'
    && physical.some((i) => i.fulfillmentStatus === 'DELIVERED')
    && physical.every((i) => ['DELIVERED', 'CANCELLED', 'RETURN_REQUESTED', 'RETURNED'].includes(i.fulfillmentStatus))

  const invoice = async () => {
    try {
      saveObjectUrl(await fetchInvoice(order.orderNumber).unwrap(), `BookMarked-Invoice-${order.orderNumber}.pdf`)
    } catch (e) {
      setMessage({ type: 'error', text: parseApiError(e).message })
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <Link to="/admin/orders" className="link text-sm">← Orders</Link>
          <h1 className="mt-1 text-2xl font-bold tracking-tight">{order.orderNumber}</h1>
          <p className="text-sm text-slate-500">Placed {formatDateTime(order.createdAt)}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge status={order.status} />
          {['CONFIRMED', 'COMPLETED', 'CANCELLED'].includes(order.status) && (
            <button type="button" className="btn-secondary py-1.5 text-sm" onClick={invoice}>Invoice (PDF)</button>
          )}
        </div>
      </div>

      {message && <Alert type={message.type}>{message.text}</Alert>}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1fr_300px]">
        <div className="space-y-4">
          {groups.map((g) => (
            <StepGroup key={`${g.status}:${g.items.map((i) => i.id).join(",")}`} order={order} status={g.status} items={g.items}
                       canUpdate={canUpdate && open} onMessage={setMessage} />
          ))}
          {done.length > 0 && (
            <section className="rounded-xl border border-slate-200 bg-white">
              <h2 className="border-b border-slate-100 px-4 py-3 text-sm font-semibold">Done</h2>
              <ul className="divide-y divide-slate-100">
                {done.map((i) => <ItemRow key={i.id} item={i} />)}
              </ul>
            </section>
          )}
          {canUpdate && canRefund && open && cancellable.length > 0 && (
            <StaffCancel order={order} items={cancellable} onMessage={setMessage} />
          )}
        </div>

        <aside className="h-fit space-y-4 text-sm">
          <Box title="Customer">
            <p className="font-medium">{order.customer?.name}</p>
            <p className="text-slate-600">{order.customer?.email}</p>
            <p className="text-slate-600">{order.customer?.phone}</p>
          </Box>
          {order.shipping && (
            <Box title="Ship to">
              <p className="font-medium">{order.shipping.name} · {order.shipping.phone}</p>
              <p className="text-slate-600">
                {[order.shipping.line1, order.shipping.line2, order.shipping.city, order.shipping.state].filter(Boolean).join(', ')} – {order.shipping.pincode}
              </p>
            </Box>
          )}
          <Box title="Payment">
            <p>{order.paymentMethod === 'COD' ? 'Cash on Delivery' : 'Online (Razorpay)'} · <span className="font-medium">{order.paymentStatus.replaceAll('_', ' ').toLowerCase()}</span></p>
            {order.razorpayPaymentId && <p className="mt-1 font-mono text-xs text-slate-500">{order.razorpayPaymentId}</p>}
            <dl className="mt-3 space-y-1">
              <Line label="Items (MRP)" value={formatPrice(order.subtotal)} />
              {Number(order.discount) > 0 && <Line label="Discount" value={`−${formatPrice(order.discount)}`} />}
              <Line label="Delivery" value={Number(order.deliveryFee) > 0 ? formatPrice(order.deliveryFee) : 'Free'} />
              <Line label="Total" value={formatPrice(order.total)} strong />
            </dl>
            {codReady && canUpdate && <CodButton order={order} onMessage={setMessage} />}
          </Box>
          {order.shipments.length > 0 && (
            <Box title="Shipments">
              <ul className="space-y-2">
                {order.shipments.map((s) => (
                  <li key={s.id}>
                    <span className="font-medium">{s.courierName}</span> · <span className="font-mono">{s.trackingNumber}</span>
                    <div className="text-xs text-slate-500">Shipped {formatDateTime(s.shippedAt)} · expected by {new Date(`${s.expectedDelivery}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })}</div>
                  </li>
                ))}
              </ul>
            </Box>
          )}
          {order.refunds.length > 0 && (
            <Box title="Refunds">
              <ul className="space-y-2">
                {order.refunds.map((r) => (
                  <li key={r.id} className="flex justify-between gap-2">
                    <span>
                      {formatPrice(r.amount)} · {r.reason.toLowerCase().replaceAll('_', ' ')}
                      {r.note && <span className="block text-xs text-red-700">{r.note}</span>}
                    </span>
                    <span className={`text-xs font-medium ${r.status === 'FAILED' ? 'text-red-700' : r.status === 'PROCESSED' ? 'text-emerald-700' : 'text-slate-600'}`}>
                      {r.status.toLowerCase()}
                    </span>
                  </li>
                ))}
              </ul>
              {order.refunds.some((r) => r.status === 'FAILED') && (
                <Link to="/admin/refunds?status=FAILED" className="link mt-2 inline-block text-xs">Retry from Refunds</Link>
              )}
            </Box>
          )}
        </aside>
      </div>
    </div>
  )
}

/** Items waiting at one step, with the action that moves them on. */
function StepGroup({ order, status, items, canUpdate, onMessage }) {
  const [selected, setSelected] = useState(() => items.map((i) => i.id))
  const [shipping, setShipping] = useState(false)
  const [process, processState] = useProcessItemsMutation()
  const [deliver, deliverState] = useDeliverItemsMutation()
  const step = NEXT_STEP[status]
  const ids = useMemo(() => selected.filter((id) => items.some((i) => i.id === id)), [selected, items])
  const busy = processState.isLoading || deliverState.isLoading

  const run = async () => {
    onMessage(null)
    try {
      if (step.action === 'process') await process({ orderNumber: order.orderNumber, itemIds: ids }).unwrap()
      else await deliver({ orderNumber: order.orderNumber, itemIds: ids }).unwrap()
      onMessage({ type: 'success', text: `${ids.length} ${ids.length === 1 ? 'item' : 'items'} marked ${step.action === 'process' ? 'packed' : 'delivered'}. The customer has been told.` })
    } catch (e) {
      onMessage({ type: 'error', text: parseApiError(e).message })
    }
  }

  return (
    <section className="rounded-xl border border-slate-200 bg-white">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-4 py-3">
        <h2 className="text-sm font-semibold">
          <span className={`mr-2 rounded-full px-2 py-0.5 text-xs ${ITEM_STATUS_TONE[status]}`}>{ITEM_STATUS_TEXT[status]}</span>
          {items.length} {items.length === 1 ? 'item' : 'items'}
        </h2>
        {canUpdate && (
          step.action === 'ship' ? (
            <button type="button" className="btn-primary py-1.5 text-sm" disabled={ids.length === 0 || shipping}
                    onClick={() => setShipping(true)}>Ship {ids.length} selected</button>
          ) : (
            <button type="button" className="btn-primary py-1.5 text-sm" disabled={ids.length === 0 || busy} onClick={run}>
              {busy && <Spinner className="h-4 w-4" />}
              {step.label} ({ids.length})
            </button>
          )
        )}
      </div>
      <ul className="divide-y divide-slate-100">
        {items.map((i) => (
          <ItemRow key={i.id} item={i} selectable={canUpdate} checked={selected.includes(i.id)}
                   onToggle={() => setSelected((s) => (s.includes(i.id) ? s.filter((x) => x !== i.id) : [...s, i.id]))} />
        ))}
      </ul>
      {shipping && (
        <ShipForm order={order} itemIds={ids} onClose={() => setShipping(false)}
                  onShipped={(text) => {
                    setShipping(false)
                    onMessage({ type: 'success', text })
                  }} />
      )}
    </section>
  )
}

function ShipForm({ order, itemIds, onClose, onShipped }) {
  const [form, setForm] = useState({ courierName: '', trackingNumber: '', expectedDelivery: todayPlus(4) })
  const [errors, setErrors] = useState({})
  const [error, setError] = useState(null)
  const [ship, { isLoading }] = useShipItemsMutation()
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }))

  const submit = async (e) => {
    e.preventDefault()
    setError(null)
    const found = {}
    if (!form.courierName.trim()) found.courierName = 'Enter the courier'
    if (!/^[A-Za-z0-9-]{4,60}$/.test(form.trackingNumber.trim())) found.trackingNumber = '4–60 letters, digits or dashes'
    if (!form.expectedDelivery || form.expectedDelivery < todayPlus(0)) found.expectedDelivery = "Can't be in the past"
    setErrors(found)
    if (Object.keys(found).length) return
    try {
      await ship({ orderNumber: order.orderNumber, itemIds, courierName: form.courierName.trim(),
        trackingNumber: form.trackingNumber.trim(), expectedDelivery: form.expectedDelivery }).unwrap()
      onShipped(`Shipped with ${form.courierName.trim()}. The customer has been emailed the tracking number.`)
    } catch (err) {
      const parsed = parseApiError(err)
      setErrors(parsed.fieldErrors)
      setError(parsed.message)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-3 border-t border-slate-100 bg-slate-50 p-4">
      {error && <Alert type="error">{error}</Alert>}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <TextInput label="Courier" value={form.courierName} onChange={set('courierName')} error={errors.courierName}
                   placeholder="Delhivery" list="couriers" />
        <TextInput label="Tracking number" value={form.trackingNumber} onChange={set('trackingNumber')}
                   error={errors.trackingNumber} />
        <TextInput label="Expected delivery" type="date" min={todayPlus(0)} value={form.expectedDelivery}
                   onChange={set('expectedDelivery')} error={errors.expectedDelivery} />
      </div>
      <datalist id="couriers">
        {['Delhivery', 'Blue Dart', 'DTDC', 'India Post', 'Ekart', 'Xpressbees'].map((c) => <option key={c} value={c} />)}
      </datalist>
      <div className="flex gap-2">
        <button type="submit" className="btn-primary" disabled={isLoading}>
          {isLoading && <Spinner className="h-4 w-4" />}
          Confirm shipment
        </button>
        <button type="button" className="btn-secondary" onClick={onClose}>Cancel</button>
      </div>
    </form>
  )
}

function StaffCancel({ order, items, onMessage }) {
  const [open, setOpen] = useState(false)
  const [selected, setSelected] = useState(() => items.map((i) => i.id))
  const [reason, setReason] = useState('')
  const [error, setError] = useState(null)
  const [cancel, { isLoading }] = useAdminCancelMutation()

  if (!open) {
    return (
      <button type="button" className="text-sm font-medium text-red-700 hover:underline" onClick={() => setOpen(true)}>
        Cancel items…
      </button>
    )
  }

  const submit = async () => {
    setError(null)
    if (!reason.trim()) {
      setError('Tell the customer why.')
      return
    }
    try {
      await cancel({ orderNumber: order.orderNumber, itemIds: selected, reason: reason.trim() }).unwrap()
      setOpen(false)
      onMessage({ type: 'success', text: order.paymentMethod === 'ONLINE'
        ? 'Cancelled and refunded. The customer has been emailed.'
        : 'Cancelled. The customer has been emailed.' })
    } catch (e) {
      setError(parseApiError(e).message)
    }
  }

  return (
    <section className="rounded-xl border border-red-200 bg-white p-4 text-sm">
      <h2 className="font-semibold">Cancel items</h2>
      <p className="text-slate-500">Stock goes back, and online payments are refunded automatically.</p>
      <div className="mt-3 space-y-1.5">
        {items.map((i) => (
          <label key={i.id} className="flex items-center gap-2">
            <input type="checkbox" checked={selected.includes(i.id)}
                   onChange={() => setSelected((s) => (s.includes(i.id) ? s.filter((x) => x !== i.id) : [...s, i.id]))} />
            {i.title} · {FORMAT_LABELS[i.format]} × {i.quantity}
          </label>
        ))}
      </div>
      <div className="mt-3">
        <TextInput label="Reason (the customer sees this)" value={reason} maxLength={200}
                   onChange={(e) => setReason(e.target.value)} placeholder="Out of stock at the warehouse" />
      </div>
      {error && <div className="mt-3"><Alert type="error">{error}</Alert></div>}
      <div className="mt-3 flex gap-2">
        <button type="button" className="btn border border-red-700 bg-red-700 text-white hover:bg-red-800"
                disabled={isLoading || selected.length === 0} onClick={submit}>
          {isLoading && <Spinner className="h-4 w-4" />}
          Cancel {selected.length} {selected.length === 1 ? 'item' : 'items'}
        </button>
        <button type="button" className="btn-secondary" onClick={() => setOpen(false)}>Close</button>
      </div>
    </section>
  )
}

function CodButton({ order, onMessage }) {
  const [collect, { isLoading }] = useCodCollectedMutation()
  return (
    <button type="button" className="btn-primary mt-3 w-full py-1.5 text-sm" disabled={isLoading}
            onClick={async () => {
              try {
                await collect({ orderNumber: order.orderNumber }).unwrap()
                onMessage({ type: 'success', text: 'Cash collected.' })
              } catch (e) {
                onMessage({ type: 'error', text: parseApiError(e).message })
              }
            }}>
      Mark {formatPrice(order.total)} collected
    </button>
  )
}

function ItemRow({ item, selectable = false, checked = false, onToggle }) {
  return (
    <li className="px-4 py-3 text-sm">
      <div className="flex items-start gap-3">
        {selectable && <input type="checkbox" className="mt-1" checked={checked} onChange={onToggle} aria-label={`Select ${item.title}`} />}
        <div className="min-w-0 flex-1">
          <p className="font-medium">{item.title}</p>
          <p className="text-slate-500">{FORMAT_LABELS[item.format]} × {item.quantity} · {formatPrice(item.lineTotal)}</p>
        </div>
        <span className={`rounded-full px-2 py-0.5 text-xs ${ITEM_STATUS_TONE[item.fulfillmentStatus]}`}>
          {ITEM_STATUS_TEXT[item.fulfillmentStatus]}
        </span>
      </div>
      {item.timeline.length > 0 && (
        <details className="mt-1 pl-7 text-xs text-slate-500">
          <summary className="cursor-pointer select-none">History</summary>
          <ol className="mt-1 space-y-0.5">
            {item.timeline.map((t, idx) => (
              <li key={idx}>{formatDateTime(t.at)} · {ITEM_STATUS_TEXT[t.status] ?? t.status}{t.note ? ` · ${t.note}` : ''}</li>
            ))}
          </ol>
        </details>
      )}
    </li>
  )
}

function Box({ title, children }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4">
      <h2 className="mb-2 font-semibold">{title}</h2>
      {children}
    </section>
  )
}

function Line({ label, value, strong = false }) {
  return (
    <div className={`flex justify-between ${strong ? 'border-t border-slate-200 pt-1 font-semibold' : ''}`}>
      <dt className={strong ? '' : 'text-slate-600'}>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}
