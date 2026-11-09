import { useEffect, useMemo, useState } from 'react'
import { useSelector } from 'react-redux'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { BRAND } from '../../brand'
import BookCover from '../../components/BookCover'
import { Alert, FullPageSpinner, Spinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { FORMAT_LABELS, formatPrice } from '../../utils/format'
import { selectUser } from '../auth/authSlice'
import { usePreviewStackQuery } from '../stack/stackApi'
import { useStack } from '../stack/useStack'
import AddressForm from './AddressForm'
import { useAddressesQuery, useCreateOrderMutation, useVerifyPaymentMutation } from './checkoutApi'
import { codBlockReason, needsAddress, parseBuyNow } from './checkoutRules'
import { newIdempotencyKey, payWithRazorpay } from './razorpay'

export default function CheckoutPage() {
  const [params] = useSearchParams()
  const buyNow = useMemo(() => parseBuyNow(params), [params])
  const stack = useStack()
  const preview = usePreviewStackQuery(buyNow ? [buyNow] : [], { skip: !buyNow })

  const view = buyNow ? preview.data : stack.view
  const loading = buyNow ? preview.isLoading : stack.isLoading || !stack.view

  if (loading) return <FullPageSpinner />
  if (buyNow && preview.isError) {
    return <Empty title="We couldn't load that book" text={parseApiError(preview.error).message} />
  }
  if (!view || view.lines.length === 0) {
    return <Empty title={`${BRAND.cart} is empty`} text="Add a book or two before checking out." />
  }
  return <Checkout view={view} buyNow={buyNow} />
}

function Checkout({ view, buyNow }) {
  const user = useSelector(selectUser)
  const navigate = useNavigate()
  const physical = needsAddress(view.lines)
  const { data: addresses, isLoading: addressesLoading } = useAddressesQuery(undefined, { skip: !physical })

  const [addressId, setAddressId] = useState(null)
  const [addingAddress, setAddingAddress] = useState(false)
  const [method, setMethod] = useState('ONLINE')
  const [error, setError] = useState(null)
  const [stage, setStage] = useState(null) // null | 'creating' | 'paying' | 'verifying'
  const [createOrder] = useCreateOrderMutation()
  const [verifyPayment] = useVerifyPaymentMutation()

  // Default to the default address once they load
  useEffect(() => {
    if (!physical || !addresses) return
    if (addresses.length === 0) setAddingAddress(true)
    else if (!addresses.some((a) => a.id === addressId)) setAddressId(addresses[0].id)
  }, [addresses, addressId, physical])

  const codReason = codBlockReason({ lines: view.lines, total: view.summary.total, emailVerified: user?.emailVerified })
  useEffect(() => {
    if (codReason && method === 'COD') setMethod('ONLINE')
  }, [codReason, method])

  // A new key whenever the choices change; the same key if the same request is re-sent
  const idempotencyKey = useMemo(newIdempotencyKey, [addressId, method])

  const blocked = view.checkoutBlocked || view.lines.some((l) => l.issue)
  const canPlace = !blocked && !stage && (!physical || addressId != null)

  const placeOrder = async () => {
    setError(null)
    setStage('creating')
    try {
      const body = { source: buyNow ? 'BUY_NOW' : 'CART', paymentMethod: method, idempotencyKey }
      if (buyNow) Object.assign(body, buyNow)
      if (physical) body.addressId = addressId
      const order = await createOrder(body).unwrap()

      if (order.status !== 'PENDING_PAYMENT') {
        navigate(`/order-success/${order.orderNumber}`, { replace: true })
        return
      }

      setStage('paying')
      const result = await payWithRazorpay(order)
      if (result.outcome !== 'paid') {
        // The order (and the reserved stock) waits 15 minutes; the order page can retry
        navigate(`/orders/${order.orderNumber}`, {
          replace: true,
          state: { paymentNote: result.outcome === 'failed' ? result.reason : 'Payment was not completed.' },
        })
        return
      }
      setStage('verifying')
      await verifyPayment(result.payload).unwrap()
      navigate(`/order-success/${order.orderNumber}`, { replace: true })
    } catch (e) {
      setError(e instanceof Error ? e.message : parseApiError(e).message)
      setStage(null)
    }
  }

  const s = view.summary
  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="text-2xl font-bold tracking-tight">Checkout</h1>
      <p className="text-sm text-slate-500">{buyNow ? 'Buying now' : `From ${BRAND.cart}`}</p>

      <div className="mt-6 grid grid-cols-1 gap-8 lg:grid-cols-[1fr_340px]">
        <div className="space-y-6">
          {physical && (
            <Section step="1" title="Delivery address">
              {addressesLoading ? (
                <Spinner className="h-5 w-5 text-brand-700" />
              ) : (
                <div className="space-y-3">
                  {addresses?.length > 0 && (
                    <div role="radiogroup" aria-label="Delivery address" className="space-y-2">
                      {addresses.map((a) => (
                        <label key={a.id}
                               className={`flex cursor-pointer gap-3 rounded-lg border p-3 text-sm ${addressId === a.id ? 'border-brand-700 bg-brand-50' : 'border-slate-200'}`}>
                          <input type="radio" name="address" className="mt-1" checked={addressId === a.id}
                                 onChange={() => setAddressId(a.id)} />
                          <span>
                            <span className="font-medium">{a.fullName}</span>
                            <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-slate-600">{a.type}</span>
                            {a.isDefault && <span className="ml-1 text-xs text-slate-500">Default</span>}
                            <span className="block text-slate-600">
                              {[a.line1, a.line2, a.city, a.state].filter(Boolean).join(', ')} – {a.pincode}
                            </span>
                            <span className="block text-slate-500">{a.phone}</span>
                          </span>
                        </label>
                      ))}
                    </div>
                  )}
                  {addingAddress ? (
                    <div className="rounded-lg border border-slate-200 p-4">
                      <AddressForm
                        defaults={{ fullName: user?.fullName ?? '', phone: user?.phone ?? '', makeDefault: !addresses?.length }}
                        submitLabel="Deliver here"
                        onSaved={(a) => {
                          setAddressId(a.id)
                          setAddingAddress(false)
                        }}
                        onCancel={addresses?.length ? () => setAddingAddress(false) : undefined}
                      />
                    </div>
                  ) : (
                    addresses?.length < 5 && (
                      <button type="button" className="link text-sm" onClick={() => setAddingAddress(true)}>
                        + Add a new address
                      </button>
                    )
                  )}
                </div>
              )}
            </Section>
          )}

          <Section step={physical ? '2' : '1'} title="Payment">
            <div role="radiogroup" aria-label="Payment method" className="space-y-2">
              <MethodOption checked={method === 'ONLINE'} onSelect={() => setMethod('ONLINE')}
                            title="Pay online" text="UPI, cards, net banking or wallets, via Razorpay (test mode)." />
              <MethodOption checked={method === 'COD'} onSelect={() => setMethod('COD')} disabled={Boolean(codReason)}
                            title="Cash on Delivery" text={codReason ?? 'Pay in cash or UPI when your books arrive.'} />
            </div>
          </Section>

          <Section step={physical ? '3' : '2'} title="Review items">
            <ul className="divide-y divide-slate-100">
              {view.lines.map((line) => (
                <li key={line.variantId} className="flex gap-3 py-3">
                  <div className="w-12 shrink-0">
                    <BookCover title={line.title} author={line.authors.join(', ')} src={line.coverImageUrl} compact />
                  </div>
                  <div className="min-w-0 flex-1 text-sm">
                    <p className="font-medium">{line.title}</p>
                    <p className="text-slate-500">{FORMAT_LABELS[line.format]} · Qty {line.quantity}</p>
                    {line.issue && <p className="font-medium text-red-700">This item can&apos;t be ordered right now.</p>}
                  </div>
                  <div className="text-sm font-semibold">{formatPrice(line.lineTotal)}</div>
                </li>
              ))}
            </ul>
            {!buyNow && <Link to="/stack" className="link text-sm">Edit {BRAND.cart}</Link>}
          </Section>
        </div>

        <aside className="h-fit space-y-4 rounded-xl border border-slate-200 bg-white p-5 lg:sticky lg:top-24">
          <h2 className="font-semibold">Order summary</h2>
          <dl className="space-y-2 text-sm">
            <Row label="Items (MRP)" value={formatPrice(s.mrpTotal)} />
            {Number(s.discount) > 0 && <Row label="Discount" value={`−${formatPrice(s.discount)}`} tone="text-emerald-700" />}
            <Row label="Delivery" value={Number(s.deliveryFee) > 0 ? formatPrice(s.deliveryFee) : 'FREE'}
                 tone={Number(s.deliveryFee) > 0 ? '' : 'text-emerald-700'} />
          </dl>
          <div className="flex items-baseline justify-between border-t border-slate-200 pt-3">
            <span className="font-semibold">To pay</span>
            <span className="text-xl font-bold">{formatPrice(s.total)}</span>
          </div>
          {error && <Alert type="error">{error}</Alert>}
          {blocked && <Alert type="warning">Some items can&apos;t be ordered. {!buyNow && <Link to="/stack" className="link">Fix them in {BRAND.cart}</Link>}</Alert>}
          <button type="button" className="btn-primary w-full" disabled={!canPlace} onClick={placeOrder}>
            {stage && <Spinner className="h-4 w-4" />}
            {stage === 'paying' ? 'Waiting for payment…' : stage === 'verifying' ? 'Confirming payment…'
              : method === 'COD' ? 'Place order' : `Pay ${formatPrice(s.total)}`}
          </button>
          {physical && addressId == null && !addressesLoading && (
            <p className="text-xs text-slate-500">Add a delivery address to continue.</p>
          )}
          <p className="text-xs text-slate-500">
            Prices are checked again when you place the order. Online orders hold your books for 15 minutes while you pay.
          </p>
        </aside>
      </div>
    </div>
  )
}

function Section({ step, title, children }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-5">
      <h2 className="mb-4 flex items-center gap-2 font-semibold">
        <span className="flex h-6 w-6 items-center justify-center rounded-full bg-brand-800 text-xs text-white">{step}</span>
        {title}
      </h2>
      {children}
    </section>
  )
}

function MethodOption({ checked, onSelect, title, text, disabled = false }) {
  return (
    <label className={`flex gap-3 rounded-lg border p-3 text-sm ${disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'} ${checked ? 'border-brand-700 bg-brand-50' : 'border-slate-200'}`}>
      <input type="radio" name="payment" className="mt-1" checked={checked} disabled={disabled} onChange={onSelect} />
      <span>
        <span className="block font-medium">{title}</span>
        <span className="text-slate-600">{text}</span>
      </span>
    </label>
  )
}

function Row({ label, value, tone = '' }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-slate-600">{label}</dt>
      <dd className={`font-medium ${tone}`}>{value}</dd>
    </div>
  )
}

function Empty({ title, text }) {
  return (
    <div className="mx-auto max-w-xl px-4 py-20 text-center">
      <h1 className="text-2xl font-bold">{title}</h1>
      <p className="mt-2 text-slate-600">{text}</p>
      <Link to="/books" className="btn-primary mt-6 inline-flex">Browse books</Link>
    </div>
  )
}
