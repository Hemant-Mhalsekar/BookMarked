import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Alert, Spinner } from '../../../components/ui'
import { parseApiError } from '../../../utils/errors'
import { formatDateTime, formatPrice } from '../../../utils/format'
import { StatusBadge } from '../../orders/OrderDetailPage'
import { useAdminOrdersQuery } from './adminOrdersApi'

// Quick filters: what staff usually look for
const ORDER_VIEWS = [
  { key: 'to-pack', label: 'To pack', params: { itemStatus: 'PLACED' } },
  { key: 'packing', label: 'Packing', params: { itemStatus: 'PROCESSING' } },
  { key: 'shipped', label: 'In transit', params: { itemStatus: 'SHIPPED' } },
  { key: 'all', label: 'All', params: {} },
  { key: 'unpaid', label: 'Awaiting payment', params: { status: 'PENDING_PAYMENT' } },
  { key: 'completed', label: 'Completed', params: { status: 'COMPLETED' } },
  { key: 'cancelled', label: 'Cancelled', params: { status: 'CANCELLED' } },
]

export default function AdminOrdersPage() {
  const [params, setParams] = useSearchParams()
  const view = ORDER_VIEWS.find((v) => v.key === params.get('view')) ?? ORDER_VIEWS[0]
  const q = params.get('q') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const [search, setSearch] = useState(q)
  useEffect(() => setSearch(q), [q])

  const { data, isLoading, isFetching, error } = useAdminOrdersQuery({ ...view.params, q: q || undefined, page })

  const update = (next) => {
    const merged = { view: view.key, q, page: String(page), ...next }
    setParams(Object.fromEntries(Object.entries(merged).filter(([k, v]) => v && !(k === 'page' && v === '0'))))
  }

  return (
    <div>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight">Orders</h1>
        <form className="flex gap-2" role="search" onSubmit={(e) => {
          e.preventDefault()
          update({ q: search.trim(), page: '0' })
        }}>
          <input className="input w-64" type="search" placeholder="Order no., email or phone" value={search}
                 onChange={(e) => setSearch(e.target.value)} aria-label="Search orders" />
          <button type="submit" className="btn-secondary">Search</button>
        </form>
      </div>

      <div className="mt-4 flex gap-2 overflow-x-auto pb-1" role="tablist" aria-label="Order views">
        {ORDER_VIEWS.map((v) => (
          <button key={v.key} type="button" role="tab" aria-selected={view.key === v.key}
                  onClick={() => update({ view: v.key, page: '0' })}
                  className={`whitespace-nowrap rounded-full px-3 py-1 text-sm font-medium ${view.key === v.key ? 'bg-brand-800 text-white' : 'bg-white text-slate-700 ring-1 ring-slate-200 hover:ring-slate-300'}`}>
            {v.label}
          </button>
        ))}
      </div>

      {error && <div className="mt-4"><Alert type="error">{parseApiError(error).message}</Alert></div>}

      <div className={`mt-4 overflow-x-auto rounded-xl border border-slate-200 bg-white ${isFetching ? 'opacity-60' : ''}`}>
        <table className="w-full min-w-[720px] text-left text-sm">
          <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">Order</th>
              <th className="px-4 py-3 font-medium">Customer</th>
              <th className="px-4 py-3 font-medium">Payment</th>
              <th className="px-4 py-3 text-right font-medium">Total</th>
              <th className="px-4 py-3 font-medium">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {isLoading && (
              <tr><td colSpan={5} className="px-4 py-10 text-center text-slate-500"><Spinner className="mx-auto h-5 w-5" /></td></tr>
            )}
            {data?.content.length === 0 && (
              <tr><td colSpan={5} className="px-4 py-10 text-center text-slate-500">No orders match.</td></tr>
            )}
            {data?.content.map((o) => (
              <tr key={o.orderNumber} className="hover:bg-slate-50">
                <td className="px-4 py-3">
                  <Link to={`/admin/orders/${o.orderNumber}`} className="font-medium text-brand-700 hover:underline">{o.orderNumber}</Link>
                  <div className="text-xs text-slate-500">{formatDateTime(o.createdAt)} · {o.itemCount} {o.itemCount === 1 ? 'item' : 'items'}</div>
                </td>
                <td className="px-4 py-3">
                  <div>{o.customerName}</div>
                  <div className="text-xs text-slate-500">{o.customerEmail}</div>
                </td>
                <td className="px-4 py-3 text-xs">
                  {o.paymentMethod === 'COD' ? 'COD' : 'Online'} · {o.paymentStatus.replaceAll('_', ' ').toLowerCase()}
                </td>
                <td className="px-4 py-3 text-right font-medium">{formatPrice(o.total)}</td>
                <td className="px-4 py-3">
                  <StatusBadge status={o.status} />
                  {o.toFulfil > 0 && o.status === 'CONFIRMED' && (
                    <div className="mt-1 text-xs text-amber-700">{o.toFulfil} to fulfil</div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {data && data.totalPages > 1 && (
        <nav className="mt-4 flex items-center justify-center gap-3 text-sm" aria-label="Pages">
          <button type="button" className="btn-secondary" disabled={page === 0} onClick={() => update({ page: String(page - 1) })}>Previous</button>
          <span>Page {page + 1} of {data.totalPages}</span>
          <button type="button" className="btn-secondary" disabled={page + 1 >= data.totalPages} onClick={() => update({ page: String(page + 1) })}>Next</button>
        </nav>
      )}
    </div>
  )
}
