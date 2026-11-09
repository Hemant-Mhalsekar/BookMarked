import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import BookCover from '../../components/BookCover'
import Can from '../../components/Can'
import { Alert, Spinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { formatDateTime, formatPrice } from '../../utils/format'
import { Pagination } from '../catalog/BooksPage'
import { useAdminBooksQuery, useSetBookActiveMutation, useSetBookFeaturedMutation } from './adminCatalogApi'

const FORMAT_SHORT = { PAPERBACK: 'PB', HARDCOVER: 'HC', EBOOK: 'E' }

export default function AdminBooksPage() {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? 'all'
  const page = Math.max(1, Number(params.get('page')) || 1)
  const [text, setText] = useState(q)

  useEffect(() => setText(q), [q])

  const { data, isFetching, error } = useAdminBooksQuery({ q: q || undefined, status, page: page - 1, size: 20 })
  const [setActive, activeState] = useSetBookActiveMutation()
  const [setFeatured, featuredState] = useSetBookFeaturedMutation()
  const toggleError = activeState.error ?? featuredState.error

  const update = (patch) => {
    const next = new URLSearchParams(params)
    Object.entries(patch).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)))
    if (!('page' in patch)) next.delete('page')
    setParams(next)
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Books</h1>
          {data && <p className="text-sm text-slate-500">{data.totalElements} total</p>}
        </div>
        <Can permission="BOOK_WRITE">
          <Link to="/admin/books/new" className="btn-primary">Add book</Link>
        </Can>
      </div>

      <div className="flex flex-wrap gap-3">
        <form
          className="flex-1"
          onSubmit={(e) => {
            e.preventDefault()
            update({ q: text.trim() })
          }}
        >
          <input type="search" className="input" placeholder="Search by title, author or ISBN" value={text}
                 onChange={(e) => setText(e.target.value)} aria-label="Search books" />
        </form>
        <select className="input w-auto" value={status} onChange={(e) => update({ status: e.target.value === 'all' ? '' : e.target.value })}
                aria-label="Status">
          <option value="all">All books</option>
          <option value="active">Visible in store</option>
          <option value="inactive">Hidden</option>
        </select>
      </div>

      {error && <Alert type="error">{parseApiError(error).message}</Alert>}
      {toggleError && <Alert type="error">{parseApiError(toggleError).message}</Alert>}

      <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
        <table className="w-full min-w-[760px] text-left text-sm">
          <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">Book</th>
              <th className="px-4 py-3 font-medium">Formats · stock</th>
              <th className="px-4 py-3 font-medium">From</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Updated</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {!data && isFetching && (
              <tr><td colSpan={6} className="py-10 text-center text-brand-800"><Spinner className="mx-auto h-6 w-6" /></td></tr>
            )}
            {data?.content.length === 0 && (
              <tr><td colSpan={6} className="py-10 text-center text-slate-500">No books found.</td></tr>
            )}
            {data?.content.map((b) => (
              <tr key={b.id} className={b.active ? '' : 'bg-slate-50/70 text-slate-500'}>
                <td className="px-4 py-3">
                  <div className="flex items-center gap-3">
                    <div className="w-10 shrink-0"><BookCover title={b.title} src={b.coverImageUrl} compact /></div>
                    <div className="min-w-0">
                      <div className="truncate font-medium text-slate-900">{b.title}</div>
                      <div className="truncate text-xs text-slate-500">{b.authors.join(', ')}</div>
                    </div>
                  </div>
                </td>
                <td className="px-4 py-3">
                  <div className="flex flex-wrap gap-1">
                    {b.variants.map((v) => (
                      <span key={v.id} title={`${v.format} · ${v.isbn}`}
                            className={`rounded px-1.5 py-0.5 text-xs font-medium ${!v.active ? 'bg-slate-100 text-slate-400 line-through' : v.stockStatus === 'OUT_OF_STOCK' ? 'bg-red-50 text-red-700' : v.stockStatus === 'LOW_STOCK' ? 'bg-amber-50 text-amber-800' : 'bg-slate-100 text-slate-700'}`}>
                        {FORMAT_SHORT[v.format]}{v.format !== 'EBOOK' && ` · ${v.stock}`}
                      </span>
                    ))}
                  </div>
                </td>
                <td className="px-4 py-3">{formatPrice(b.fromPrice)}</td>
                <td className="px-4 py-3">
                  <div className="flex flex-col items-start gap-1">
                    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${b.active ? 'bg-emerald-50 text-emerald-700' : 'bg-slate-200 text-slate-600'}`}>
                      {b.active ? 'Visible' : 'Hidden'}
                    </span>
                    {b.featured && <span className="rounded-full bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-800">Featured</span>}
                  </div>
                </td>
                <td className="whitespace-nowrap px-4 py-3 text-xs text-slate-500">{formatDateTime(b.updatedAt)}</td>
                <td className="px-4 py-3">
                  <Can permission="BOOK_WRITE">
                    <div className="flex justify-end gap-2 text-xs">
                      <Link to={`/admin/books/${b.id}/edit`} className="link">Edit</Link>
                      <button type="button" className="link" onClick={() => setFeatured({ id: b.id, featured: !b.featured })}>
                        {b.featured ? 'Unfeature' : 'Feature'}
                      </button>
                      <button type="button" className={b.active ? 'font-medium text-red-700 hover:underline' : 'link'}
                              onClick={() => setActive({ id: b.id, active: !b.active })}>
                        {b.active ? 'Hide' : 'Show'}
                      </button>
                    </div>
                  </Can>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {data && <Pagination page={page} totalPages={data.totalPages} onChange={(p) => update({ page: String(p) })} />}
    </div>
  )
}
