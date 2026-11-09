import { useState } from 'react'
import { Link } from 'react-router-dom'
import BookCover from '../../components/BookCover'
import { FullPageSpinner } from '../../components/ui'
import { formatDateTime } from '../../utils/format'
import { ReviewForm, StarRow } from './BookReviews'
import { useDeleteReviewMutation, useMyReviewsQuery } from './reviewsApi'

const STATUS_NOTE = {
  HIDDEN: 'Hidden while our team checks reports about it.',
}

/** /account/reviews — your reviews, and delivered books waiting for one. */
export default function MyReviewsPage() {
  const { data, isLoading } = useMyReviewsQuery()
  const [editing, setEditing] = useState(null)
  const [del] = useDeleteReviewMutation()
  if (isLoading || !data) return <FullPageSpinner />

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-bold tracking-tight">My reviews</h1>

      {data.waiting.length > 0 && (
        <section>
          <h2 className="text-lg font-semibold">Waiting for your review</h2>
          <ul className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
            {data.waiting.map((b) => (
              <li key={b.bookId} className="flex items-center gap-3 rounded-xl border border-slate-200 bg-white p-3">
                <div className="w-10 shrink-0"><BookCover title={b.title} src={b.coverImageUrl} compact /></div>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-semibold">{b.title}</p>
                  <p className="text-xs text-slate-500">Delivered {formatDateTime(b.deliveredAt)}</p>
                </div>
                <Link to={`/book/${b.slug}#reviews`} className="btn-primary py-1.5 text-xs">Rate it</Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section>
        <h2 className="text-lg font-semibold">Your reviews</h2>
        {data.reviews.length === 0 ? (
          <p className="mt-3 text-sm text-slate-500">You haven&apos;t reviewed anything yet.</p>
        ) : (
          <ul className="mt-3 space-y-3">
            {data.reviews.map((r) => (
              <li key={r.id} className="rounded-xl border border-slate-200 bg-white p-4">
                <div className="flex gap-3">
                  <Link to={`/book/${r.slug}`} className="w-12 shrink-0"><BookCover title={r.bookTitle} src={r.coverImageUrl} compact /></Link>
                  <div className="min-w-0 flex-1 text-sm">
                    <Link to={`/book/${r.slug}#reviews`} className="font-semibold hover:text-brand-700">{r.bookTitle}</Link>
                    <div className="mt-0.5 flex flex-wrap items-center gap-2">
                      <StarRow value={r.rating} size="text-sm" />
                      {r.title && <span className="font-medium">{r.title}</span>}
                    </div>
                    {editing === r.id ? (
                      <div className="mt-3">
                        <ReviewForm slug={r.slug} existing={r} onDone={() => setEditing(null)} />
                      </div>
                    ) : (
                      <>
                        <p className="mt-1 whitespace-pre-line text-slate-700">{r.comment}</p>
                        <p className="mt-2 text-xs text-slate-500">
                          {formatDateTime(r.createdAt)}{r.helpfulCount > 0 && ` · ${r.helpfulCount} found it helpful`}
                        </p>
                        {STATUS_NOTE[r.status] && <p className="mt-1 text-xs text-amber-700">{STATUS_NOTE[r.status]}</p>}
                        <div className="mt-2 flex gap-3 text-xs">
                          <button type="button" className="link" onClick={() => setEditing(r.id)}>Edit</button>
                          <button type="button" className="font-medium text-red-700 hover:underline" onClick={() => del(r.id)}>Delete</button>
                        </div>
                      </>
                    )}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
