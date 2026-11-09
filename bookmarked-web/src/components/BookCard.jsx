import { Link } from 'react-router-dom'
import { formatPrice } from '../utils/format'
import BookCover from './BookCover'

export function Stars({ rating = 0, count }) {
  const value = Number(rating) || 0
  if (!count) return <span className="text-xs text-slate-400">No ratings yet</span>
  return (
    <span className="flex items-center gap-1 text-xs text-slate-600" aria-label={`Rated ${value} out of 5`}>
      <span className="text-amber-500" aria-hidden="true">★</span>
      <span className="font-medium text-slate-800">{value.toFixed(1)}</span>
      <span>({count.toLocaleString('en-IN')})</span>
    </span>
  )
}

export default function BookCard({ book }) {
  const author = book.authors?.join(', ')
  const out = book.badges?.includes('OUT_OF_STOCK')
  return (
    <Link
      to={`/book/${book.slug}`}
      className="group flex flex-col gap-3 rounded-xl border border-transparent p-2 transition hover:border-slate-200 hover:bg-white hover:shadow-sm"
    >
      <div className={`relative ${out ? 'opacity-60' : ''}`}>
        <BookCover title={book.title} author={author} src={book.coverImageUrl} />
        {book.discountPercent > 0 && (
          <span className="absolute left-2 top-2 rounded bg-emerald-600 px-1.5 py-0.5 text-[11px] font-semibold text-white">
            {book.discountPercent}% off
          </span>
        )}
      </div>
      <div className="space-y-1 px-1">
        <h3 className="line-clamp-2 text-sm font-semibold leading-snug text-slate-900 group-hover:text-brand-700">
          {book.title}
        </h3>
        <p className="line-clamp-1 text-xs text-slate-500">{author}</p>
        <Stars rating={book.avgRating} count={book.ratingCount} />
        <div className="flex items-baseline gap-2 pt-0.5">
          <span className="text-xs text-slate-500">from</span>
          <span className="font-semibold text-slate-900">{formatPrice(book.fromPrice)}</span>
          {book.discountPercent > 0 && <span className="text-xs text-slate-400 line-through">{formatPrice(book.mrp)}</span>}
        </div>
        <div className="flex flex-wrap gap-1 pt-0.5">
          {out && <Badge tone="slate">Out of stock</Badge>}
          {!out && book.badges?.includes('LOW_STOCK') && <Badge tone="amber">Only {book.stockLeft} left</Badge>}
          {book.badges?.includes('EBOOK_AVAILABLE') && <Badge tone="brand">E-book</Badge>}
        </div>
      </div>
    </Link>
  )
}

const tones = {
  brand: 'bg-brand-50 text-brand-800',
  amber: 'bg-amber-50 text-amber-800',
  slate: 'bg-slate-100 text-slate-600',
}

export function Badge({ tone = 'slate', children }) {
  return <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${tones[tone]}`}>{children}</span>
}

export function BookCardSkeleton() {
  return (
    <div className="flex animate-pulse flex-col gap-3 p-2" aria-hidden="true">
      <div className="aspect-[2/3] w-full rounded-md bg-slate-200" />
      <div className="h-4 w-4/5 rounded bg-slate-200" />
      <div className="h-3 w-1/2 rounded bg-slate-200" />
      <div className="h-4 w-1/3 rounded bg-slate-200" />
    </div>
  )
}
