import { useState } from 'react'
import { useSelector } from 'react-redux'
import { Link, useLocation } from 'react-router-dom'
import { Alert, Spinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { selectUser } from '../auth/authSlice'
import {
  distributionPercents,
  REVIEW_SORTS,
  useCreateReviewMutation,
  useDeleteReviewMutation,
  useReportReviewMutation,
  useReviewsQuery,
  useReviewSummaryQuery,
  useUpdateReviewMutation,
  useVoteHelpfulMutation,
  validateReview,
} from './reviewsApi'

const dateFmt = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })

export function StarRow({ value, size = 'text-base' }) {
  return (
    <span className={`${size} tracking-tight`} aria-label={`${value} out of 5 stars`}>
      {[1, 2, 3, 4, 5].map((i) => (
        <span key={i} className={i <= Math.round(value) ? 'text-amber-500' : 'text-slate-300'} aria-hidden="true">★</span>
      ))}
    </span>
  )
}

/** Ratings and reviews on the book page (anchor #reviews). */
export default function BookReviews({ slug }) {
  const user = useSelector(selectUser)
  const location = useLocation()
  const { data: summary } = useReviewSummaryQuery(slug)
  const [sort, setSort] = useState('HELPFUL')
  const [stars, setStars] = useState(null)
  const [page, setPage] = useState(0)
  const [editing, setEditing] = useState(false)
  const { data: list, isFetching } = useReviewsQuery({ slug, sort, stars, page })

  if (!summary) return null
  const bars = distributionPercents(summary.distribution)
  const mine = summary.myReview

  return (
    <section id="reviews" className="scroll-mt-24">
      <h2 className="text-xl font-bold tracking-tight">Ratings &amp; reviews</h2>
      <div className="mt-4 grid grid-cols-1 gap-8 md:grid-cols-[260px_1fr]">
        <div className="space-y-4">
          <div>
            <p className="text-4xl font-bold">{summary.ratingCount ? Number(summary.avgRating).toFixed(1) : '–'}</p>
            <StarRow value={Number(summary.avgRating)} size="text-xl" />
            <p className="text-sm text-slate-500">
              {summary.ratingCount ? `${summary.ratingCount} ${summary.ratingCount === 1 ? 'rating' : 'ratings'}` : 'No ratings yet'}
            </p>
          </div>
          <ul className="space-y-1.5" aria-label="Rating breakdown">
            {bars.map((b) => (
              <li key={b.stars}>
                <button type="button" disabled={!b.count}
                        onClick={() => {
                          setStars(stars === b.stars ? null : b.stars)
                          setPage(0)
                        }}
                        className={`flex w-full items-center gap-2 rounded text-xs ${stars === b.stars ? 'font-semibold text-brand-800' : 'text-slate-600'} disabled:cursor-default`}>
                  <span className="w-8 text-left">{b.stars} ★</span>
                  <span className="h-2 flex-1 rounded-full bg-slate-200">
                    <span className="block h-2 rounded-full bg-amber-400" style={{ width: `${b.percent}%` }} />
                  </span>
                  <span className="w-8 text-right tabular-nums">{b.count}</span>
                </button>
              </li>
            ))}
          </ul>

          {summary.canReview || editing ? (
            <ReviewForm slug={slug} existing={editing ? mine : null} onDone={() => setEditing(false)} />
          ) : mine ? (
            <div className="rounded-lg bg-brand-50 p-3 text-sm">
              <p className="font-medium text-brand-900">You rated this {mine.rating} ★</p>
              <button type="button" className="link mt-1 text-sm" onClick={() => setEditing(true)}>Edit your review</button>
            </div>
          ) : !user ? (
            <p className="text-sm text-slate-600">
              <Link to="/login" state={{ from: { pathname: location.pathname, search: '' } }} className="link">Log in</Link> to review a book you bought.
            </p>
          ) : (
            <p className="text-sm text-slate-500">{summary.cannotReviewReason}</p>
          )}
        </div>

        <div>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm text-slate-600">
              {stars ? <>Showing {stars}-star reviews · <button type="button" className="link" onClick={() => setStars(null)}>show all</button></> : null}
            </p>
            <label className="flex items-center gap-2 text-sm text-slate-600">
              Sort
              <select className="input w-auto py-1" value={sort} onChange={(e) => {
                setSort(e.target.value)
                setPage(0)
              }}>
                {REVIEW_SORTS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
              </select>
            </label>
          </div>
          {list?.content.length === 0 && (
            <p className="py-8 text-sm text-slate-500">
              {summary.ratingCount ? 'No reviews match.' : 'No reviews yet. Bought this book? Be the first to review it.'}
            </p>
          )}
          <ul className={`mt-2 divide-y divide-slate-200 ${isFetching ? 'opacity-60' : ''}`}>
            {list?.content.map((r) => <ReviewItem key={r.id} review={r} canInteract={Boolean(user) && user.accountType !== 'STAFF'} />)}
          </ul>
          {list && list.totalPages > 1 && (
            <nav className="mt-4 flex items-center gap-3 text-sm" aria-label="Review pages">
              <button type="button" className="btn-secondary py-1" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
              <span>{page + 1} / {list.totalPages}</span>
              <button type="button" className="btn-secondary py-1" disabled={page + 1 >= list.totalPages} onClick={() => setPage(page + 1)}>Next</button>
            </nav>
          )}
        </div>
      </div>
    </section>
  )
}

function ReviewItem({ review: r, canInteract }) {
  const [vote, { isLoading: voting }] = useVoteHelpfulMutation()
  const [report] = useReportReviewMutation()
  const [del, { isLoading: deleting }] = useDeleteReviewMutation()
  const [reporting, setReporting] = useState(false)
  const [reason, setReason] = useState('')
  const [note, setNote] = useState(null)

  const sendReport = async () => {
    try {
      await report({ id: r.id, reason: reason.trim() }).unwrap()
      setReporting(false)
      setNote('Thanks. Our team will take a look.')
    } catch (e) {
      setNote(parseApiError(e).message)
    }
  }

  return (
    <li className="py-4">
      <div className="flex flex-wrap items-center gap-2">
        <StarRow value={r.rating} size="text-sm" />
        {r.title && <span className="font-semibold">{r.title}</span>}
      </div>
      <p className="mt-1 text-xs text-slate-500">
        {r.authorName}
        {r.verifiedPurchase && <span className="ml-2 rounded bg-emerald-50 px-1.5 py-0.5 font-medium text-emerald-700">Verified purchase</span>}
        <span className="ml-2">{dateFmt.format(new Date(r.createdAt))}{r.edited ? ' · edited' : ''}</span>
      </p>
      <p className="mt-2 whitespace-pre-line text-sm text-slate-800">{r.comment}</p>
      <div className="mt-2 flex flex-wrap items-center gap-3 text-xs">
        {r.helpfulCount > 0 && <span className="text-slate-500">{r.helpfulCount} found this helpful</span>}
        {r.mine ? (
          <button type="button" className="font-medium text-red-700 hover:underline" disabled={deleting}
                  onClick={() => del(r.id)}>Delete my review</button>
        ) : canInteract ? (
          <>
            <button type="button" disabled={voting} aria-pressed={r.votedHelpful}
                    onClick={() => vote({ id: r.id, add: !r.votedHelpful })}
                    className={`rounded-full border px-2.5 py-0.5 ${r.votedHelpful ? 'border-brand-700 bg-brand-50 text-brand-800' : 'border-slate-300 text-slate-600 hover:border-slate-400'}`}>
              {r.votedHelpful ? '✓ Helpful' : 'Helpful'}
            </button>
            {!r.reported && !reporting && (
              <button type="button" className="text-slate-500 hover:underline" onClick={() => setReporting(true)}>Report</button>
            )}
          </>
        ) : null}
        {note && <span className="text-slate-600">{note}</span>}
      </div>
      {reporting && (
        <div className="mt-2 flex flex-wrap gap-2">
          <input className="input max-w-sm py-1 text-sm" placeholder="What's wrong with this review?" maxLength={200}
                 value={reason} onChange={(e) => setReason(e.target.value)} aria-label="Reason for reporting" />
          <button type="button" className="btn-secondary py-1 text-sm" disabled={reason.trim().length < 3} onClick={sendReport}>Send report</button>
          <button type="button" className="text-sm text-slate-500" onClick={() => setReporting(false)}>Cancel</button>
        </div>
      )}
    </li>
  )
}

export function ReviewForm({ slug, existing, onDone }) {
  const [rating, setRating] = useState(existing?.rating ?? 0)
  const [hover, setHover] = useState(0)
  const [title, setTitle] = useState(existing?.title ?? '')
  const [comment, setComment] = useState(existing?.comment ?? '')
  const [errors, setErrors] = useState({})
  const [error, setError] = useState(null)
  const [create, createState] = useCreateReviewMutation()
  const [update, updateState] = useUpdateReviewMutation()
  const saving = createState.isLoading || updateState.isLoading

  const submit = async (e) => {
    e.preventDefault()
    setError(null)
    const found = validateReview({ rating, title, comment })
    setErrors(found)
    if (Object.keys(found).length) return
    const body = { rating, title: title.trim() || null, comment: comment.trim() }
    try {
      if (existing) await update({ id: existing.id, ...body }).unwrap()
      else await create({ slug, ...body }).unwrap()
      onDone?.()
    } catch (err) {
      const parsed = parseApiError(err)
      setErrors(parsed.fieldErrors)
      setError(parsed.message)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-3 rounded-lg border border-slate-200 bg-white p-4 text-sm">
      <p className="font-semibold">{existing ? 'Edit your review' : 'Review this book'}</p>
      <div role="radiogroup" aria-label="Your rating" className="flex gap-1" onMouseLeave={() => setHover(0)}>
        {[1, 2, 3, 4, 5].map((i) => (
          <button key={i} type="button" role="radio" aria-checked={rating === i} aria-label={`${i} star${i > 1 ? 's' : ''}`}
                  onMouseEnter={() => setHover(i)} onClick={() => setRating(i)}
                  className={`text-2xl leading-none ${(hover || rating) >= i ? 'text-amber-500' : 'text-slate-300'}`}>★</button>
        ))}
      </div>
      {errors.rating && <p className="text-xs text-red-600">{errors.rating}</p>}
      <input className={`input ${errors.title ? 'input-error' : ''}`} placeholder="Title (optional)" maxLength={100}
             value={title} onChange={(e) => setTitle(e.target.value)} aria-label="Review title" />
      <div>
        <textarea className={`input ${errors.comment ? 'input-error' : ''}`} rows={4} maxLength={2000}
                  placeholder="What did you think? What should other readers know?"
                  value={comment} onChange={(e) => setComment(e.target.value)} aria-label="Your review" />
        <p className={`text-xs ${errors.comment ? 'text-red-600' : 'text-slate-400'}`}>{errors.comment ?? `${comment.length}/2000`}</p>
      </div>
      {error && <Alert type="error">{error}</Alert>}
      <div className="flex gap-2">
        <button type="submit" className="btn-primary py-1.5 text-sm" disabled={saving}>
          {saving && <Spinner className="h-4 w-4" />}
          {existing ? 'Save changes' : 'Post review'}
        </button>
        {existing && <button type="button" className="btn-secondary py-1.5 text-sm" onClick={onDone}>Cancel</button>}
      </div>
    </form>
  )
}
