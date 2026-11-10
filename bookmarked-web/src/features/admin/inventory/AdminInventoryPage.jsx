import { useEffect, useState } from 'react'
import { useSelector } from 'react-redux'
import { Link, useSearchParams } from 'react-router-dom'
import { Alert, Spinner } from '../../../components/ui'
import { parseApiError } from '../../../utils/errors'
import { formatDateTime } from '../../../utils/format'
import { selectHasPermission } from '../../auth/authSlice'
import {
  formatLabel,
  MOVEMENT_LABELS,
  useAdjustStockMutation,
  useInventoryQuery,
  useStockLedgerQuery,
  validateAdjustment,
} from './inventoryApi'

const FILTERS = [['ALL', 'All'], ['LOW', 'Low stock'], ['OUT', 'Sold out']]

/** Printed editions: what's on the shelf, what's held for payment, what sold. */
export default function AdminInventoryPage() {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const filter = params.get('filter') ?? 'ALL'
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const [search, setSearch] = useState(q)
  useEffect(() => setSearch(q), [q])
  const canEdit = useSelector(selectHasPermission('INVENTORY_UPDATE'))
  const { data, isLoading, isFetching, error } = useInventoryQuery({ q, filter, page })
  const [open, setOpen] = useState(null)   // { id, mode: 'adjust' | 'history' }
  const [message, setMessage] = useState(null)
  const update = (next) => setParams(Object.fromEntries(Object.entries({ q, filter, page: String(page), ...next })
    .filter(([k, v]) => v && !(k === 'page' && v === '0') && !(k === 'filter' && v === 'ALL'))))

  return (
    <div>
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Inventory</h1>
          <p className="text-sm text-slate-500">
            Printed books only. &ldquo;Held&rdquo; copies are in orders waiting for payment and come back if it lapses.
          </p>
        </div>
        <form className="flex gap-2" role="search" onSubmit={(e) => {
          e.preventDefault()
          update({ q: search.trim(), page: '0' })
        }}>
          <input className="input w-64" type="search" placeholder="Title or ISBN" value={search}
                 onChange={(e) => setSearch(e.target.value)} aria-label="Search inventory" />
          <button type="submit" className="btn-secondary">Search</button>
        </form>
      </div>

      <div className="mt-4 flex flex-wrap gap-2" role="tablist" aria-label="Stock level">
        {FILTERS.map(([key, label]) => {
          const count = key === 'LOW' ? data?.lowCount : key === 'OUT' ? data?.outCount : null
          return (
            <button key={key} type="button" role="tab" aria-selected={filter === key}
                    onClick={() => update({ filter: key, page: '0' })}
                    className={`rounded-full px-3 py-1 text-sm font-medium ${filter === key ? 'bg-brand-800 text-white' : 'bg-white text-slate-700 ring-1 ring-slate-200'}`}>
              {label}{count ? <span className={`ml-1.5 rounded-full px-1.5 text-xs ${filter === key ? 'bg-white/20' : key === 'OUT' ? 'bg-red-100 text-red-800' : 'bg-amber-100 text-amber-900'}`}>{count}</span> : null}
            </button>
          )
        })}
      </div>

      <div className="mt-4 space-y-3">
        {error && <Alert type="error">{parseApiError(error).message}</Alert>}
        {message && <Alert type="success">{message}</Alert>}
      </div>

      <div className={`mt-4 overflow-x-auto rounded-xl border border-slate-200 bg-white ${isFetching ? 'opacity-60' : ''}`}>
        <table className="w-full min-w-[760px] text-left text-sm">
          <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">Book</th>
              <th className="px-4 py-3 text-right font-medium">In stock</th>
              <th className="px-4 py-3 text-right font-medium">Held</th>
              <th className="px-4 py-3 text-right font-medium">Sold (30 days)</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {isLoading && <tr><td colSpan={5} className="py-10"><Spinner className="mx-auto h-5 w-5" /></td></tr>}
            {data?.rows.content.length === 0 && (
              <tr><td colSpan={5} className="px-4 py-10 text-center text-slate-500">
                {filter === 'ALL' ? 'No printed books match.' : 'Nothing here. Every book is well stocked.'}
              </td></tr>
            )}
            {data?.rows.content.map((r) => (
              <StockRow key={r.variantId} r={r} canEdit={canEdit} open={open?.id === r.variantId ? open.mode : null}
                        onOpen={(mode) => {
                          setMessage(null)
                          setOpen(open?.id === r.variantId && open.mode === mode ? null : { id: r.variantId, mode })
                        }}
                        onDone={(text) => {
                          setOpen(null)
                          setMessage(text)
                        }} />
            ))}
          </tbody>
        </table>
      </div>
      {data && data.rows.totalPages > 1 && (
        <nav className="mt-4 flex items-center justify-center gap-3 text-sm" aria-label="Pages">
          <button type="button" className="btn-secondary" disabled={page === 0} onClick={() => update({ page: String(page - 1) })}>Previous</button>
          <span>Page {page + 1} of {data.rows.totalPages}</span>
          <button type="button" className="btn-secondary" disabled={page + 1 >= data.rows.totalPages} onClick={() => update({ page: String(page + 1) })}>Next</button>
        </nav>
      )}
    </div>
  )
}

function StockRow({ r, canEdit, open, onOpen, onDone }) {
  const level = r.stock === 0 ? 'text-red-700' : r.lowStock ? 'text-amber-700' : ''
  return (
    <>
      <tr className={r.active ? '' : 'text-slate-400'}>
        <td className="px-4 py-3">
          <Link to={`/admin/books/${r.bookId}/edit`} className="font-medium text-brand-700 hover:underline">{r.title}</Link>
          <p className="text-xs text-slate-500">{formatLabel(r.format)} · ISBN {r.isbn}{!r.active && ' · hidden from the store'}</p>
        </td>
        <td className={`px-4 py-3 text-right text-base font-semibold tabular-nums ${level}`}>
          {r.stock}
          {r.stock === 0 ? <span className="block text-xs font-medium">Sold out</span>
            : r.lowStock ? <span className="block text-xs font-medium">Low</span> : null}
        </td>
        <td className="px-4 py-3 text-right tabular-nums">{r.reserved}</td>
        <td className="px-4 py-3 text-right tabular-nums">{r.sold30}</td>
        <td className="px-4 py-3 text-right">
          <div className="flex justify-end gap-3 text-xs">
            <button type="button" className="link" aria-expanded={open === 'history'} onClick={() => onOpen('history')}>History</button>
            {canEdit && <button type="button" className="link" aria-expanded={open === 'adjust'} onClick={() => onOpen('adjust')}>Adjust</button>}
          </div>
        </td>
      </tr>
      {open && (
        <tr>
          <td colSpan={5} className="bg-slate-50 px-4 py-4">
            {open === 'adjust' ? <AdjustForm r={r} onDone={onDone} /> : <Ledger variantId={r.variantId} />}
          </td>
        </tr>
      )}
    </>
  )
}

function AdjustForm({ r, onDone }) {
  const [reason, setReason] = useState('RESTOCK')
  const [direction, setDirection] = useState('remove')
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState({})
  const [error, setError] = useState(null)
  const [adjust, { isLoading }] = useAdjustStockMutation()
  const signed = reason === 'ADJUSTMENT' && direction === 'remove' ? -Number(amount) : Number(amount)

  const submit = async (e) => {
    e.preventDefault()
    setError(null)
    const found = validateAdjustment({ reason, quantity: amount === '' ? 0 : signed, note }, r.stock)
    setErrors(found)
    if (Object.keys(found).length) return
    try {
      const after = await adjust({ variantId: r.variantId, reason, quantity: signed, note: note.trim() || null }).unwrap()
      onDone(`${r.title} (${formatLabel(r.format)}): stock is now ${after.stock}.`)
    } catch (err) {
      const parsed = parseApiError(err)
      setErrors(parsed.fieldErrors)
      setError(parsed.message)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-wrap items-start gap-3 text-sm">
      <fieldset className="flex gap-2">
        <legend className="sr-only">Reason</legend>
        {[['RESTOCK', 'Restock'], ['ADJUSTMENT', 'Correct the count']].map(([v, l]) => (
          <label key={v} className={`cursor-pointer rounded-lg border px-3 py-2 ${reason === v ? 'border-brand-700 bg-white font-medium' : 'border-slate-200'}`}>
            <input type="radio" name={`reason-${r.variantId}`} value={v} checked={reason === v} className="sr-only"
                   onChange={() => setReason(v)} />{l}
          </label>
        ))}
      </fieldset>
      {reason === 'ADJUSTMENT' && (
        <select className="input w-auto" value={direction} onChange={(e) => setDirection(e.target.value)} aria-label="Add or remove">
          <option value="remove">Remove</option>
          <option value="add">Add</option>
        </select>
      )}
      <div>
        <input className={`input w-28 ${errors.quantity ? 'input-error' : ''}`} inputMode="numeric" placeholder="Copies"
               aria-label="Number of copies" value={amount} onChange={(e) => setAmount(e.target.value.replace(/\D/g, ''))} />
        {errors.quantity && <p className="mt-1 text-xs text-red-600">{errors.quantity}</p>}
      </div>
      <div className="min-w-[220px] flex-1">
        <input className={`input ${errors.note ? 'input-error' : ''}`} maxLength={200} aria-label="Note"
               placeholder={reason === 'RESTOCK' ? 'Note (optional), e.g. supplier invoice' : 'Why? e.g. damaged in storage'}
               value={note} onChange={(e) => setNote(e.target.value)} />
        {errors.note && <p className="mt-1 text-xs text-red-600">{errors.note}</p>}
      </div>
      <button type="submit" className="btn-primary" disabled={isLoading}>
        {isLoading && <Spinner className="h-4 w-4" />}Save
      </button>
      {amount && !errors.quantity && (
        <p className="w-full text-xs text-slate-600">
          {r.stock} → <strong>{Math.max(0, r.stock + (Number.isFinite(signed) ? signed : 0))}</strong> in stock
        </p>
      )}
      {error && <div className="w-full"><Alert type="error">{error}</Alert></div>}
    </form>
  )
}

function Ledger({ variantId }) {
  const [page, setPage] = useState(0)
  const { data, isLoading, error } = useStockLedgerQuery({ variantId, page })
  if (isLoading) return <Spinner className="mx-auto h-5 w-5" />
  if (error) return <Alert type="error">{parseApiError(error).message}</Alert>
  if (!data.content.length) return <p className="text-sm text-slate-500">No stock changes recorded yet.</p>
  return (
    <div>
      <ul className="divide-y divide-slate-200 text-sm">
        {data.content.map((m) => (
          <li key={m.id} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 py-2">
            <span className={`w-14 text-right font-semibold tabular-nums ${m.change > 0 ? 'text-emerald-700' : 'text-red-700'}`}>
              {m.change > 0 ? `+${m.change}` : m.change}
            </span>
            <span className="font-medium">{MOVEMENT_LABELS[m.reason] ?? m.reason}</span>
            {m.reference && <Link to={`/admin/orders/${m.reference}`} className="link text-xs">{m.reference}</Link>}
            {m.note && <span className="text-slate-600">{m.note}</span>}
            <span className="ml-auto text-xs text-slate-500">{m.by ?? 'System'} · {formatDateTime(m.at)}</span>
          </li>
        ))}
      </ul>
      {data.totalPages > 1 && (
        <div className="mt-2 flex gap-3 text-xs">
          <button type="button" className="link" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</button>
          <button type="button" className="link" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Older</button>
        </div>
      )}
    </div>
  )
}
