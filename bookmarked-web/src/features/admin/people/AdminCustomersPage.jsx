import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Alert, Spinner } from '../../../components/ui'
import { parseApiError } from '../../../utils/errors'
import { formatPrice, timeAgo } from '../../../utils/format'
import { useCustomersQuery } from './peopleApi'

const STATUSES = [['ALL', 'All'], ['ACTIVE', 'Active'], ['BLOCKED', 'Blocked'], ['DELETED', 'Deleted']]

/** Customers (USER_MANAGE): search by name, email or phone. */
export default function AdminCustomersPage() {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? 'ALL'
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const [search, setSearch] = useState(q)
  useEffect(() => setSearch(q), [q])
  const { data, isLoading, isFetching, error } = useCustomersQuery({ q: q || undefined, status, page })
  const update = (next) => setParams(Object.fromEntries(Object.entries({ q, status, page: String(page), ...next })
    .filter(([k, v]) => v && !(k === 'page' && v === '0') && !(k === 'status' && v === 'ALL'))))

  return (
    <div>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight">Customers</h1>
        <form className="flex gap-2" role="search" onSubmit={(e) => {
          e.preventDefault()
          update({ q: search.trim(), page: '0' })
        }}>
          <input className="input w-64" type="search" placeholder="Name, email or phone" value={search}
                 onChange={(e) => setSearch(e.target.value)} aria-label="Search customers" />
          <button type="submit" className="btn-secondary">Search</button>
        </form>
      </div>
      <div className="mt-4 flex gap-2" role="tablist" aria-label="Status">
        {STATUSES.map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={status === key} onClick={() => update({ status: key, page: '0' })}
                  className={`rounded-full px-3 py-1 text-sm font-medium ${status === key ? 'bg-brand-800 text-white' : 'bg-white text-slate-700 ring-1 ring-slate-200'}`}>
            {label}
          </button>
        ))}
      </div>
      {error && <div className="mt-4"><Alert type="error">{parseApiError(error).message}</Alert></div>}
      <div className={`mt-4 overflow-x-auto rounded-xl border border-slate-200 bg-white ${isFetching ? 'opacity-60' : ''}`}>
        <table className="w-full min-w-[720px] text-left text-sm">
          <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">Customer</th>
              <th className="px-4 py-3 text-right font-medium">Orders</th>
              <th className="px-4 py-3 text-right font-medium">Spent</th>
              <th className="px-4 py-3 font-medium">Joined</th>
              <th className="px-4 py-3 font-medium">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {isLoading && <tr><td colSpan={5} className="py-10"><Spinner className="mx-auto h-5 w-5" /></td></tr>}
            {data?.content.length === 0 && <tr><td colSpan={5} className="px-4 py-10 text-center text-slate-500">No customers match.</td></tr>}
            {data?.content.map((c) => (
              <tr key={c.id} className="hover:bg-slate-50">
                <td className="px-4 py-3">
                  <Link to={`/admin/customers/${c.id}`} className="font-medium text-brand-700 hover:underline">{c.fullName}</Link>
                  <p className="text-xs text-slate-500">{c.email} · {c.phone}</p>
                </td>
                <td className="px-4 py-3 text-right">{c.orders}</td>
                <td className="px-4 py-3 text-right">{formatPrice(c.spent)}</td>
                <td className="px-4 py-3 text-xs">{timeAgo(c.createdAt)}</td>
                <td className="px-4 py-3"><CustomerStatus c={c} /></td>
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

export function CustomerStatus({ c }) {
  if (c.deleted) return <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">Deleted</span>
  if (!c.enabled) return <span className="rounded-full bg-red-50 px-2 py-0.5 text-xs font-medium text-red-700">Blocked</span>
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${c.emailVerified ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-800'}`}>
      {c.emailVerified ? 'Active' : 'Email not verified'}
    </span>
  )
}
