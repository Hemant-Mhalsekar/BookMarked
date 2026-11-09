import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Spinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { formatDateTime } from '../../utils/format'
import { StarRow } from '../reviews/BookReviews'
import { useModerateMutation, useModerationQueueQuery } from '../reviews/reviewsApi'

const FILTERS = [
  { key: 'ATTENTION', label: 'Needs attention' },
  { key: 'HIDDEN', label: 'Hidden' },
  { key: 'REMOVED', label: 'Removed' },
  { key: 'ALL', label: 'All' },
]

/** Reported reviews: three reports hide one automatically; a moderator restores or removes it. */
export default function AdminReviewsPage() {
  const [filter, setFilter] = useState('ATTENTION')
  const [page, setPage] = useState(0)
  const { data, isLoading, isFetching, error } = useModerationQueueQuery({ filter, page })
  const [moderate, { isLoading: busy }] = useModerateMutation()
  const [message, setMessage] = useState(null)

  const act = async (r, action) => {
    setMessage(null)
    try {
      await moderate({ id: r.id, action }).unwrap()
      setMessage({ type: 'success', text: action === 'restore' ? 'Review restored and reports cleared.' : 'Review removed.' })
    } catch (e) {
      setMessage({ type: 'error', text: parseApiError(e).message })
    }
  }

  return (
    <div>
      <h1 className="text-2xl font-bold tracking-tight">Reviews</h1>
      <p className="text-sm text-slate-500">Reviews hide themselves after 3 reports. Restore the fair ones; remove the rest.</p>
      <div className="mt-4 flex gap-2 overflow-x-auto pb-1" role="tablist" aria-label="Review filter">
        {FILTERS.map((f) => (
          <button key={f.key} type="button" role="tab" aria-selected={filter === f.key}
                  onClick={() => {
                    setFilter(f.key)
                    setPage(0)
                  }}
                  className={`whitespace-nowrap rounded-full px-3 py-1 text-sm font-medium ${filter === f.key ? 'bg-brand-800 text-white' : 'bg-white text-slate-700 ring-1 ring-slate-200'}`}>
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
        {data?.content.length === 0 && <li className="py-10 text-center text-slate-500">Nothing to moderate.</li>}
        {data?.content.map((r) => (
          <li key={r.id} className="rounded-xl border border-slate-200 bg-white p-4 text-sm">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div className="min-w-0">
                <Link to={`/book/${r.slug}#reviews`} className="font-semibold text-brand-700 hover:underline">{r.bookTitle}</Link>
                <div className="mt-1 flex flex-wrap items-center gap-2">
                  <StarRow value={r.rating} size="text-sm" />
                  {r.title && <span className="font-medium">{r.title}</span>}
                </div>
                <p className="mt-1 whitespace-pre-line text-slate-700">{r.comment}</p>
                <p className="mt-1 text-xs text-slate-500">{r.authorName} · {r.authorEmail} · {formatDateTime(r.createdAt)}</p>
              </div>
              <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${
                r.status === 'HIDDEN' ? 'bg-amber-50 text-amber-800' : r.status === 'REMOVED' ? 'bg-slate-100 text-slate-600' : 'bg-emerald-50 text-emerald-700'}`}>
                {r.status.toLowerCase()}
              </span>
            </div>
            {r.reportCount > 0 && (
              <div className="mt-3 rounded-lg bg-red-50 p-3 text-xs text-red-900">
                <p className="font-medium">{r.reportCount} {r.reportCount === 1 ? 'report' : 'reports'}</p>
                <ul className="mt-1 list-disc pl-4">
                  {r.reportReasons.map((reason, i) => <li key={i}>{reason}</li>)}
                </ul>
              </div>
            )}
            <div className="mt-3 flex gap-2">
              {r.status !== 'VISIBLE' || r.reportCount > 0 ? (
                <button type="button" className="btn-secondary py-1.5 text-sm" disabled={busy} onClick={() => act(r, 'restore')}>
                  {r.status === 'VISIBLE' ? 'Keep (dismiss reports)' : 'Restore'}
                </button>
              ) : null}
              {r.status !== 'REMOVED' && (
                <button type="button" className="btn border border-red-700 bg-red-700 py-1.5 text-sm text-white hover:bg-red-800" disabled={busy}
                        onClick={() => act(r, 'remove')}>Remove</button>
              )}
            </div>
          </li>
        ))}
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
