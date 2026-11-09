import { useState } from 'react'
import { useSelector } from 'react-redux'
import { Link, useSearchParams } from 'react-router-dom'
import { Alert, Spinner } from '../../../components/ui'
import { parseApiError } from '../../../utils/errors'
import { FORMAT_LABELS, formatDateTime, formatPrice } from '../../../utils/format'
import { selectUser } from '../../auth/authSlice'
import { RETURN_REASON_TEXT } from '../../orders/orderRules'
import { useReturnActionMutation, useReturnsQuery } from './adminOrdersApi'

const FILTERS = [
  { key: 'REQUESTED', label: 'To review' },
  { key: 'APPROVED', label: 'Awaiting pickup' },
  { key: 'PICKED_UP', label: 'In transit' },
  { key: 'RECEIVED', label: 'To refund' },
  { key: 'REFUNDED', label: 'Refunded' },
  { key: 'REJECTED', label: 'Declined' },
  { key: 'ALL', label: 'All' },
]

const TONE = {
  REQUESTED: 'bg-amber-50 text-amber-800',
  APPROVED: 'bg-sky-50 text-sky-800',
  PICKED_UP: 'bg-indigo-50 text-indigo-800',
  RECEIVED: 'bg-violet-50 text-violet-800',
  REFUNDED: 'bg-emerald-50 text-emerald-700',
  REJECTED: 'bg-slate-100 text-slate-600',
}

export default function AdminReturnsPage() {
  const [params, setParams] = useSearchParams()
  const status = FILTERS.some((f) => f.key === params.get('status')) ? params.get('status') : 'REQUESTED'
  const [page, setPage] = useState(0)
  const { data, isLoading, isFetching, error } = useReturnsQuery({ status, page })
  const [message, setMessage] = useState(null)

  return (
    <div>
      <h1 className="text-2xl font-bold tracking-tight">Returns</h1>
      <p className="text-sm text-slate-500">7-day returns, refund only. Approve, collect, check the book, then refund.</p>

      <div className="mt-4 flex gap-2 overflow-x-auto pb-1" role="tablist" aria-label="Return status">
        {FILTERS.map((f) => (
          <button key={f.key} type="button" role="tab" aria-selected={status === f.key}
                  onClick={() => {
                    setPage(0)
                    setMessage(null)
                    setParams({ status: f.key })
                  }}
                  className={`whitespace-nowrap rounded-full px-3 py-1 text-sm font-medium ${status === f.key ? 'bg-brand-800 text-white' : 'bg-white text-slate-700 ring-1 ring-slate-200 hover:ring-slate-300'}`}>
            {f.label}
          </button>
        ))}
      </div>

      <div className="mt-4 space-y-3">
        {error && <Alert type="error">{parseApiError(error).message}</Alert>}
        {message && <Alert type={message.type}>{message.text}</Alert>}
      </div>

      <ul className={`mt-4 space-y-3 ${isFetching ? 'opacity-60' : ''}`}>
        {isLoading && <li className="py-10"><Spinner className="mx-auto h-5 w-5" /></li>}
        {data?.content.length === 0 && <li className="py-10 text-center text-slate-500">Nothing here.</li>}
        {data?.content.map((r) => <ReturnCard key={r.id} r={r} onMessage={setMessage} />)}
      </ul>

      {data && data.totalPages > 1 && (
        <nav className="mt-4 flex items-center justify-center gap-3 text-sm" aria-label="Pages">
          <button type="button" className="btn-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span>Page {page + 1} of {data.totalPages}</span>
          <button type="button" className="btn-secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </nav>
      )}
    </div>
  )
}

function ReturnCard({ r, onMessage }) {
  const user = useSelector(selectUser)
  const canRefund = user.permissions.includes('REFUND_ISSUE')
  const [act, { isLoading }] = useReturnActionMutation()
  const [mode, setMode] = useState(null) // null | 'reject' | 'refund'
  const [note, setNote] = useState('')
  const [reference, setReference] = useState('')
  const [error, setError] = useState(null)

  const run = async (action, body = {}, success) => {
    setError(null)
    try {
      await act({ id: r.id, action, ...body }).unwrap()
      setMode(null)
      onMessage({ type: 'success', text: success })
    } catch (e) {
      setError(parseApiError(e).message)
    }
  }

  return (
    <li className="rounded-xl border border-slate-200 bg-white p-4 text-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="font-medium">{r.title} <span className="font-normal text-slate-500">· {FORMAT_LABELS[r.format]} × {r.quantity}</span></p>
          <p className="text-slate-600">
            <Link to={`/admin/orders/${r.orderNumber}`} className="link">{r.orderNumber}</Link> · {r.customerName} · {r.customerEmail}
          </p>
          <p className="mt-1">
            <span className="font-medium">{RETURN_REASON_TEXT[r.reason] ?? r.reason}</span>
            {r.comment && <span className="text-slate-600"> — “{r.comment}”</span>}
          </p>
          <p className="mt-1 text-xs text-slate-500">
            Requested {formatDateTime(r.requestedAt)} · refund {formatPrice(r.refundAmount)}{' '}
            {r.refundMethod === 'UPI' ? <>by UPI to <span className="font-mono">{r.refundUpiId}</span></> : 'via Razorpay'}
            {r.restocked != null && ` · ${r.restocked ? 'restocked' : 'written off'}`}
            {r.upiReference && <> · UPI ref <span className="font-mono">{r.upiReference}</span></>}
          </p>
          {r.adminNote && <p className="mt-1 text-xs text-slate-600">Note: {r.adminNote}</p>}
        </div>
        <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${TONE[r.status] ?? ''}`}>
          {r.status.replace('_', ' ').toLowerCase()}
        </span>
      </div>

      <div className="mt-3 flex flex-wrap gap-2">
        {r.status === 'REQUESTED' && (
          <button type="button" className="btn-primary py-1.5 text-sm" disabled={isLoading}
                  onClick={() => run('approve', {}, 'Approved. Arrange the pickup; the customer has been told.')}>Approve</button>
        )}
        {(r.status === 'REQUESTED' || r.status === 'APPROVED') && (
          <button type="button" className="btn-secondary py-1.5 text-sm" onClick={() => setMode(mode === 'reject' ? null : 'reject')}>Decline…</button>
        )}
        {r.status === 'APPROVED' && (
          <button type="button" className="btn-primary py-1.5 text-sm" disabled={isLoading}
                  onClick={() => run('picked-up', {}, 'Marked picked up.')}>Picked up</button>
        )}
        {r.status === 'PICKED_UP' && (
          <>
            <button type="button" className="btn-primary py-1.5 text-sm" disabled={isLoading}
                    onClick={() => run('received', { restock: true }, 'Received and back in stock.')}>Received · restock</button>
            <button type="button" className="btn-secondary py-1.5 text-sm" disabled={isLoading}
                    onClick={() => run('received', { restock: false, note: 'Written off' }, 'Received and written off.')}>Received · write off</button>
          </>
        )}
        {r.status === 'RECEIVED' && canRefund && (
          r.refundMethod === 'RAZORPAY' ? (
            <button type="button" className="btn-primary py-1.5 text-sm" disabled={isLoading}
                    onClick={() => run('refund', {}, `${formatPrice(r.refundAmount)} refunded through Razorpay.`)}>
              Refund {formatPrice(r.refundAmount)}
            </button>
          ) : (
            <button type="button" className="btn-primary py-1.5 text-sm" onClick={() => setMode(mode === 'refund' ? null : 'refund')}>
              Record UPI refund…
            </button>
          )
        )}
        {isLoading && <Spinner className="h-4 w-4 self-center text-brand-700" />}
      </div>

      {mode === 'reject' && (
        <div className="mt-3 space-y-2 rounded-lg bg-slate-50 p-3">
          <label className="block">
            <span className="text-slate-700">Reason (the customer sees this)</span>
            <input className="input mt-1" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          <button type="button" className="btn border border-red-700 bg-red-700 py-1.5 text-sm text-white hover:bg-red-800"
                  disabled={isLoading || note.trim().length < 3}
                  onClick={() => run('reject', { note: note.trim() }, 'Return declined. The customer has been told why.')}>
            Decline return
          </button>
        </div>
      )}
      {mode === 'refund' && (
        <div className="mt-3 space-y-2 rounded-lg bg-slate-50 p-3">
          <p className="text-slate-700">
            Send {formatPrice(r.refundAmount)} to <span className="font-mono">{r.refundUpiId}</span> from the store&apos;s UPI app, then enter the reference.
          </p>
          <input className="input" placeholder="UPI reference (UTR)" maxLength={60} value={reference}
                 onChange={(e) => setReference(e.target.value)} aria-label="UPI reference" />
          <button type="button" className="btn-primary py-1.5 text-sm" disabled={isLoading || !reference.trim()}
                  onClick={() => run('refund', { upiReference: reference.trim() }, 'UPI refund recorded. The customer has been told.')}>
            Save refund
          </button>
        </div>
      )}
      {error && <div className="mt-3"><Alert type="error">{error}</Alert></div>}
    </li>
  )
}
