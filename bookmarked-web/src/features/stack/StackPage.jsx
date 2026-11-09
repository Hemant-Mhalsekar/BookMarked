import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { BRAND } from '../../brand'
import BookCover from '../../components/BookCover'
import { Alert, FullPageSpinner, Spinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { FORMAT_LABELS, formatPrice } from '../../utils/format'
import { useStack } from './useStack'

const ISSUE_TEXT = {
  UNAVAILABLE: 'No longer available. Remove it to continue.',
  OUT_OF_STOCK: 'Out of stock. Remove it or save it for later.',
  QUANTITY_EXCEEDS_STOCK: (line) => `Only ${line.maxQuantity} available. Lower the quantity to continue.`,
  ALREADY_OWNED: 'Already in your library. Remove it to continue.',
}

export default function StackPage() {
  const stack = useStack()
  const [error, setError] = useState(null)
  const [busyLine, setBusyLine] = useState(null)
  const navigate = useNavigate()

  if (stack.isStaff) {
    return (
      <div className="mx-auto max-w-xl px-4 py-16 text-center">
        <h1 className="text-2xl font-bold">Staff accounts can&apos;t shop</h1>
        <p className="mt-2 text-slate-600">Log in with a customer account to build a Stack.</p>
      </div>
    )
  }
  if (stack.isLoading || !stack.view) return <FullPageSpinner />

  const { view } = stack
  const run = async (line, action) => {
    setError(null)
    setBusyLine(line?.variantId ?? 'all')
    try {
      await action()
    } catch (e) {
      setError(parseApiError(e).message)
    } finally {
      setBusyLine(null)
    }
  }

  if (view.lines.length === 0) {
    return (
      <div className="mx-auto max-w-xl px-4 py-20 text-center">
        <h1 className="text-2xl font-bold">{BRAND.cart} is empty</h1>
        <p className="mt-2 text-slate-600">Find something worth coming back to.</p>
        <div className="mt-6 flex justify-center gap-3">
          <Link to="/books" className="btn-primary">Browse books</Link>
          {stack.isCustomer && <Link to="/bookmarks" className="btn-secondary">Your {BRAND.wishlist}</Link>}
        </div>
      </div>
    )
  }

  const s = view.summary
  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="text-2xl font-bold tracking-tight">{BRAND.cart}</h1>
      <p className="text-sm text-slate-500">{view.totalQuantity} {view.totalQuantity === 1 ? 'item' : 'items'}</p>

      <div className="mt-4 space-y-3">
        {view.notice && <Alert type="info">{view.notice}</Alert>}
        {error && <Alert type="error">{error}</Alert>}
        {view.pricesChanged && (
          <Alert type="warning">
            Some prices have changed since you added these books. The new prices are shown below.{' '}
            <button type="button" className="link" onClick={() => run(null, stack.acceptPrices)}>OK, got it</button>
          </Alert>
        )}
      </div>

      <div className="mt-6 grid grid-cols-1 gap-8 lg:grid-cols-[1fr_340px]">
        <ul className="divide-y divide-slate-200 rounded-xl border border-slate-200 bg-white">
          {view.lines.map((line) => (
            <StackLine
              key={line.variantId}
              line={line}
              busy={busyLine === line.variantId}
              canSave={stack.isCustomer}
              onQuantity={(q) => run(line, () => stack.setQuantity(line, q))}
              onRemove={() => run(line, () => stack.remove(line))}
              onSave={() => run(line, () => stack.saveForLater(line))}
            />
          ))}
        </ul>

        <aside className="h-fit space-y-4 rounded-xl border border-slate-200 bg-white p-5 lg:sticky lg:top-24">
          <h2 className="font-semibold">Summary</h2>
          <dl className="space-y-2 text-sm">
            <Row label={`Price (${s.itemCount} ${s.itemCount === 1 ? 'item' : 'items'}, MRP)`} value={formatPrice(s.mrpTotal)} />
            {Number(s.discount) > 0 && <Row label="Discount" value={`−${formatPrice(s.discount)}`} tone="text-emerald-700" />}
            <Row label="Delivery" value={Number(s.deliveryFee) > 0 ? formatPrice(s.deliveryFee) : 'FREE'}
                 tone={Number(s.deliveryFee) > 0 ? '' : 'text-emerald-700'} />
          </dl>
          {Number(s.amountForFreeDelivery) > 0 && (
            <p className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-900">
              Add {formatPrice(s.amountForFreeDelivery)} more for free delivery.
            </p>
          )}
          <div className="flex items-baseline justify-between border-t border-slate-200 pt-3">
            <span className="font-semibold">Total</span>
            <span className="text-xl font-bold">{formatPrice(s.total)}</span>
          </div>
          {Number(s.discount) > 0 && (
            <p className="text-sm font-medium text-emerald-700">You save {formatPrice(s.discount)} on this order</p>
          )}
          {stack.isGuest ? (
            <Link to="/login" state={{ from: { pathname: '/stack' } }} className="btn-primary w-full">
              Log in to check out
            </Link>
          ) : (
            <button type="button" className="btn-primary w-full" disabled={view.checkoutBlocked}
                    onClick={() => navigate('/checkout')}>
              Proceed to checkout
            </button>
          )}
          {view.checkoutBlocked && view.lines.some((l) => l.issue) && (
            <p className="text-xs text-red-700">Fix the highlighted items before checking out.</p>
          )}
          {stack.isGuest && (
            <p className="text-xs text-slate-500">{BRAND.cart} is saved in this browser and joins your account when you log in.</p>
          )}
        </aside>
      </div>
    </div>
  )
}

function StackLine({ line, busy, canSave, onQuantity, onRemove, onSave }) {
  const issue = line.issue ? ISSUE_TEXT[line.issue] : null
  const issueText = typeof issue === 'function' ? issue(line) : issue
  const isEbook = line.format === 'EBOOK'
  const options = Array.from({ length: Math.max(line.quantity, line.maxQuantity, 1) }, (_, i) => i + 1)

  return (
    <li className={`flex gap-4 p-4 ${line.issue ? 'bg-red-50/40' : ''}`}>
      <Link to={`/book/${line.slug}`} className="w-20 shrink-0">
        <BookCover title={line.title} author={line.authors.join(', ')} src={line.coverImageUrl} className="text-[8px]" compact />
      </Link>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <Link to={`/book/${line.slug}`} className="font-semibold text-slate-900 hover:text-brand-700">{line.title}</Link>
            <p className="text-sm text-slate-500">{line.authors.join(', ')}</p>
            <p className="mt-1 text-xs font-medium uppercase tracking-wide text-slate-500">{FORMAT_LABELS[line.format]}</p>
          </div>
          <div className="text-right">
            <div className="font-semibold">{formatPrice(line.lineTotal)}</div>
            <div className="text-xs text-slate-500">
              {formatPrice(line.unitPrice)} each
              {Number(line.mrp) > Number(line.unitPrice) && <span className="ml-1 line-through">{formatPrice(line.mrp)}</span>}
            </div>
            {line.previousPrice != null && (
              <div className="text-xs font-medium text-amber-700">was {formatPrice(line.previousPrice)}</div>
            )}
          </div>
        </div>

        {issueText && <p className="mt-2 text-sm font-medium text-red-700">{issueText}</p>}
        {!issueText && line.stockStatus === 'LOW_STOCK' && (
          <p className="mt-2 text-xs font-medium text-amber-700">Only {line.stockLeft} left in stock</p>
        )}

        <div className="mt-3 flex flex-wrap items-center gap-4 text-sm">
          {isEbook ? (
            <span className="text-xs text-slate-500">E-book · 1 copy</span>
          ) : (
            <label className="flex items-center gap-2 text-slate-600">
              Qty
              <select className="input w-auto py-1" value={line.quantity} disabled={busy || line.issue === 'UNAVAILABLE' || line.issue === 'OUT_OF_STOCK'}
                      onChange={(e) => onQuantity(Number(e.target.value))}>
                {options.map((n) => <option key={n} value={n}>{n}</option>)}
              </select>
            </label>
          )}
          {canSave && <button type="button" className="link" disabled={busy} onClick={onSave}>Save for later</button>}
          <button type="button" className="font-medium text-red-700 hover:underline" disabled={busy} onClick={onRemove}>Remove</button>
          {busy && <Spinner className="h-4 w-4 text-brand-700" />}
        </div>
      </div>
    </li>
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
