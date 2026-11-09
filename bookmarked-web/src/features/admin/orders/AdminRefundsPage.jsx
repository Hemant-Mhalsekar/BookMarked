import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Alert, Spinner } from '../../../components/ui'
import { parseApiError } from '../../../utils/errors'
import { formatDateTime, formatPrice } from '../../../utils/format'
import { useRefundsQuery, useRetryRefundMutation } from './adminOrdersApi'

const FILTERS = [
  { key: '', label: 'All' },
  { key: 'FAILED', label: 'Failed' },
  { key: 'PENDING', label: 'Pending' },
  { key: 'PROCESSED', label: 'Processed' },
]

const TONE = {
  FAILED: 'bg-red-50 text-red-700',
  PENDING: 'bg-amber-50 text-amber-800',
  RETRYING: 'bg-amber-50 text-amber-800',
  PROCESSED: 'bg-emerald-50 text-emerald-700',
}

export default function AdminRefundsPage() {
  const [params, setParams] = useSearchParams()
  const status = FILTERS.some((f) => f.key === params.get('status')) ? params.get('status') : ''
  const [page, setPage] = useState(0)
  const { data, isLoading, isFetching, error } = useRefundsQuery({ status: status || undefined, page })
  const [retry] = useRetryRefundMutation()
  const [busy, setBusy] = useState(null)
  const [message, setMessage] = useState(null)

  const runRetry = async (r) => {
    setBusy(r.id)
    setMessage(null)
    try {
      const res = await retry(r.id).unwrap()
      setMessage(res.status === 'FAILED'
        ? { type: 'error', text: `Razorpay refused again: ${res.note}` }
        : { type: 'success', text: `Refund of ${formatPrice(r.amount)} for ${r.orderNumber} sent to Razorpay.` })
    } catch (e) {
      setMessage({ type: 'error', text: parseApiError(e).message })
    } finally {
      setBusy(null)
    }
  }

  return (
    <div>
      <h1 className="text-2xl font-bold tracking-tight">Refunds</h1>
      <p className="text-sm text-slate-500">
        Refunds go out automatically through Razorpay. When Razorpay refuses or can&apos;t be reached, they show here as failed so you can retry.
      </p>

      <div className="mt-4 flex gap-2" role="tablist" aria-label="Refund status">
        {FILTERS.map((f) => (
          <button key={f.label} type="button" role="tab" aria-selected={status === f.key}
                  onClick={() => {
                    setPage(0)
                    setParams(f.key ? { status: f.key } : {})
                  }}
                  className={`rounded-full px-3 py-1 text-sm font-medium ${status === f.key ? 'bg-brand-800 text-white' : 'bg-white text-slate-700 ring-1 ring-slate-200'}`}>
            {f.label}
          </button>
        ))}
      </div>

      <div className="mt-4 space-y-3">
        {error && <Alert type="error">{parseApiError(error).message}</Alert>}
        {message && <Alert type={message.type}>{message.text}</Alert>}
      </div>

      <div className={`mt-4 overflow-x-auto rounded-xl border border-slate-200 bg-white ${isFetching ? 'opacity-60' : ''}`}>
        <table className="w-full min-w-[640px] text-left text-sm">
          <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">Order</th>
              <th className="px-4 py-3 font-medium">Reason</th>
              <th className="px-4 py-3 text-right font-medium">Amount</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {isLoading && <tr><td colSpan={5} className="px-4 py-10"><Spinner className="mx-auto h-5 w-5" /></td></tr>}
            {data?.content.length === 0 && (
              <tr><td colSpan={5} className="px-4 py-10 text-center text-slate-500">No refunds here.</td></tr>
            )}
            {data?.content.map((r) => (
              <tr key={r.id}>
                <td className="px-4 py-3">
                  <Link to={`/admin/orders/${r.orderNumber}`} className="font-medium text-brand-700 hover:underline">{r.orderNumber}</Link>
                  <div className="text-xs text-slate-500">{formatDateTime(r.createdAt)}</div>
                </td>
                <td className="px-4 py-3 text-xs">{r.reason.replaceAll('_', ' ').toLowerCase()}</td>
                <td className="px-4 py-3 text-right font-medium">{formatPrice(r.amount)}</td>
                <td className="px-4 py-3">
                  <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${TONE[r.status] ?? ''}`}>{r.status.toLowerCase()}</span>
                  {r.note && <div className="mt-1 max-w-xs text-xs text-red-700">{r.note}</div>}
                </td>
                <td className="px-4 py-3 text-right">
                  {r.status === 'FAILED' && (
                    <button type="button" className="btn-secondary py-1 text-xs" disabled={busy === r.id} onClick={() => runRetry(r)}>
                      {busy === r.id && <Spinner className="h-3 w-3" />}
                      Retry
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

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
