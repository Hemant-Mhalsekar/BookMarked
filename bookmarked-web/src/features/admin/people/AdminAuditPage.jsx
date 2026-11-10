import { useState } from 'react'
import { Alert, Spinner } from '../../../components/ui'
import { parseApiError } from '../../../utils/errors'
import { formatDateTime } from '../../../utils/format'
import { actionLabel, describeChange, useAuditQuery } from './peopleApi'

const AREAS = [
  ['', 'Everything'], ['ORDERS', 'Orders'], ['RETURNS', 'Returns'], ['REFUNDS', 'Refunds'], ['BOOKS', 'Books'],
  ['VARIANTS', 'E-book files'], ['CATEGORIES', 'Categories'], ['REVIEWS', 'Reviews'], ['STAFF', 'Staff'], ['CUSTOMERS', 'Customers'],
]

/** Every staff change, newest first (AUDIT_VIEW). Refused attempts are listed too. */
export default function AdminAuditPage() {
  const [entityType, setEntityType] = useState('')
  const [action, setAction] = useState('')
  const [applied, setApplied] = useState('')
  const [page, setPage] = useState(0)
  const { data, isLoading, isFetching, error } = useAuditQuery({ entityType, action: applied, page })

  return (
    <div>
      <h1 className="text-2xl font-bold tracking-tight">Audit log</h1>
      <p className="text-sm text-slate-500">Every change made by staff, including attempts that were refused. Entries can&apos;t be edited or deleted.</p>
      <div className="mt-4 flex flex-wrap items-end gap-3">
        <label className="text-sm">
          <span className="block text-slate-600">Area</span>
          <select className="input mt-1 w-auto" value={entityType} onChange={(e) => {
            setEntityType(e.target.value)
            setPage(0)
          }}>
            {AREAS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </select>
        </label>
        <form className="flex items-end gap-2" onSubmit={(e) => {
          e.preventDefault()
          setApplied(action.trim())
          setPage(0)
        }}>
          <label className="text-sm">
            <span className="block text-slate-600">Action contains</span>
            <input className="input mt-1 w-48" value={action} placeholder="e.g. ship, block" onChange={(e) => setAction(e.target.value)} />
          </label>
          <button type="submit" className="btn-secondary">Filter</button>
        </form>
      </div>
      {error && <div className="mt-4"><Alert type="error">{parseApiError(error).message}</Alert></div>}
      <div className={`mt-4 overflow-x-auto rounded-xl border border-slate-200 bg-white ${isFetching ? 'opacity-60' : ''}`}>
        <table className="w-full min-w-[820px] text-left text-sm">
          <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">When</th>
              <th className="px-4 py-3 font-medium">Who</th>
              <th className="px-4 py-3 font-medium">Action</th>
              <th className="px-4 py-3 font-medium">Record</th>
              <th className="px-4 py-3 font-medium">Details</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 align-top">
            {isLoading && <tr><td colSpan={5} className="py-10"><Spinner className="mx-auto h-5 w-5" /></td></tr>}
            {data?.content.length === 0 && <tr><td colSpan={5} className="px-4 py-10 text-center text-slate-500">Nothing logged yet.</td></tr>}
            {data?.content.map((e) => {
              const details = describeChange(e.oldValue, e.newValue)
              const refused = /refused/.test(details)
              return (
                <tr key={e.id} className={refused ? 'bg-red-50/40' : ''}>
                  <td className="whitespace-nowrap px-4 py-3 text-xs">{formatDateTime(e.createdAt)}<br /><span className="text-slate-400">{e.ipAddress}</span></td>
                  <td className="px-4 py-3"><p className="font-medium">{e.staffName}</p><p className="text-xs text-slate-500">{e.staffEmail}</p></td>
                  <td className="px-4 py-3 font-medium">{actionLabel(e.action)}</td>
                  <td className="px-4 py-3 text-xs">{e.entityType.toLowerCase()} {e.entityId !== '-' && <span className="font-mono">{e.entityId}</span>}</td>
                  <td className={`max-w-sm px-4 py-3 text-xs ${refused ? 'text-red-800' : 'text-slate-600'}`}>{details}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      {data && data.totalPages > 1 && (
        <nav className="mt-4 flex items-center justify-center gap-3 text-sm" aria-label="Pages">
          <button type="button" className="btn-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</button>
          <span>Page {page + 1} of {data.totalPages}</span>
          <button type="button" className="btn-secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Older</button>
        </nav>
      )}
    </div>
  )
}
