import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Alert, FullPageSpinner } from '../../../components/ui'
import { parseApiError } from '../../../utils/errors'
import { formatDateTime, formatPrice } from '../../../utils/format'
import { StatusBadge } from '../../orders/OrderDetailPage'
import { CustomerStatus } from './AdminCustomersPage'
import { useCustomerActionMutation, useCustomerQuery } from './peopleApi'

export default function AdminCustomerPage() {
  const { id } = useParams()
  const { data, isLoading, error } = useCustomerQuery(id)
  const [act, { isLoading: busy }] = useCustomerActionMutation()
  const [message, setMessage] = useState(null)

  if (isLoading) return <FullPageSpinner />
  if (error) return <Alert type="error">{parseApiError(error).message}</Alert>
  const c = data.customer

  const run = async (action, text) => {
    setMessage(null)
    try {
      await act({ id: c.id, action }).unwrap()
      setMessage({ type: 'success', text })
    } catch (e) {
      setMessage({ type: 'error', text: parseApiError(e).message })
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <Link to="/admin/customers" className="link text-sm">← Customers</Link>
          <h1 className="mt-1 text-2xl font-bold tracking-tight">{c.fullName}</h1>
          <p className="text-sm text-slate-500">{c.email} · {c.phone} · joined {formatDateTime(c.createdAt)}</p>
        </div>
        <CustomerStatus c={c} />
      </div>
      {message && <Alert type={message.type}>{message.text}</Alert>}

      {!c.deleted && (
        <div className="flex flex-wrap gap-2">
          <button type="button" className="btn-secondary" disabled={busy || data.activeSessions === 0}
                  onClick={() => run('sign-out', `${c.fullName} has been signed out on every device.`)}>
            Sign out everywhere ({data.activeSessions})
          </button>
          <button type="button" disabled={busy}
                  className={c.enabled ? 'btn border border-red-700 bg-red-700 text-white hover:bg-red-800' : 'btn-primary'}
                  onClick={() => run(c.enabled ? 'block' : 'unblock',
                    c.enabled ? `${c.fullName} is blocked and signed out.` : `${c.fullName} can log in again.`)}>
            {c.enabled ? 'Block account' : 'Unblock account'}
          </button>
        </div>
      )}

      <section className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        {[['Orders', c.orders], ['Spent', formatPrice(c.spent)], ['Returns', data.returns], ['Reviews', data.reviews],
          ['E-books', data.libraryBooks]].map(([label, value]) => (
          <div key={label} className="rounded-xl border border-slate-200 bg-white p-4">
            <p className="text-xl font-bold">{value}</p>
            <p className="text-sm text-slate-500">{label}</p>
          </div>
        ))}
      </section>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <section>
          <h2 className="font-semibold">Recent orders</h2>
          <ul className="mt-2 divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white text-sm">
            {data.recentOrders.length === 0 && <li className="p-4 text-slate-500">No orders.</li>}
            {data.recentOrders.map((o) => (
              <li key={o.orderNumber} className="flex flex-wrap items-center gap-2 px-4 py-3">
                <Link to={`/admin/orders/${o.orderNumber}`} className="font-medium text-brand-700 hover:underline">{o.orderNumber}</Link>
                <span className="text-xs text-slate-500">{formatDateTime(o.createdAt)}</span>
                <span className="ml-auto">{formatPrice(o.total)}</span>
                <StatusBadge status={o.status} />
              </li>
            ))}
          </ul>
        </section>
        <section>
          <h2 className="font-semibold">Recent sign-ins</h2>
          <ul className="mt-2 divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white text-sm">
            {data.recentLogins.length === 0 && <li className="p-4 text-slate-500">No sign-ins yet.</li>}
            {data.recentLogins.map((l, i) => (
              <li key={i} className="flex flex-wrap items-center gap-2 px-4 py-3">
                <span className={`h-2 w-2 rounded-full ${l.success ? 'bg-emerald-500' : 'bg-red-500'}`} aria-label={l.success ? 'Success' : 'Failed'} />
                <span>{formatDateTime(l.at)}</span>
                <span className="text-xs text-slate-500">{l.ipAddress ?? 'unknown IP'}</span>
                <span className="w-full truncate text-xs text-slate-400" title={l.device}>{l.device}</span>
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  )
}
