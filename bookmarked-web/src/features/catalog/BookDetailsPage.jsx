import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Stars } from '../../components/BookCard'
import BookCover from '../../components/BookCover'
import { Alert, FullPageSpinner } from '../../components/ui'
import NotFoundPage from '../../pages/NotFoundPage'
import { parseApiError } from '../../utils/errors'
import { FORMAT_LABELS, formatPrice } from '../../utils/format'
import { useBookQuery } from './catalogApi'

function stockText(v) {
  switch (v.stockStatus) {
    case 'DIGITAL':
      return { text: 'Instant access after purchase', tone: 'text-brand-700' }
    case 'LOW_STOCK':
      return { text: `Only ${v.stockLeft} left in stock`, tone: 'text-amber-700' }
    case 'OUT_OF_STOCK':
      return { text: 'Out of stock', tone: 'text-red-700' }
    default:
      return { text: 'In stock', tone: 'text-emerald-700' }
  }
}

/**
 * Book page. Adding to Your Stack, Bookmarks and reviews arrive in Step 3;
 * this version shows formats, prices, stock and details.
 */
export default function BookDetailsPage() {
  const { slug } = useParams()
  const { data: book, isLoading, error } = useBookQuery(slug)
  const [selected, setSelected] = useState(null)

  if (isLoading) return <FullPageSpinner />
  if (error?.status === 404) return <NotFoundPage />
  if (error) {
    return <div className="mx-auto max-w-3xl px-4 py-10"><Alert type="error">{parseApiError(error).message}</Alert></div>
  }

  const variant = book.variants.find((v) => v.format === selected) ?? book.variants[0]
  const stock = stockText(variant)
  const authors = book.authors.map((a) => a.name).join(', ')

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <nav className="text-sm text-slate-500" aria-label="Breadcrumb">
        <Link to="/books" className="hover:text-slate-800">Books</Link>
        {book.categories[0] && (
          <>
            {' / '}
            <Link to={`/books?category=${book.categories[0].slug}`} className="hover:text-slate-800">{book.categories[0].name}</Link>
          </>
        )}
      </nav>

      <div className="mt-6 grid grid-cols-1 gap-10 md:grid-cols-[280px_1fr]">
        <div className="mx-auto w-56 md:w-full">
          <BookCover title={book.title} author={authors} src={book.coverImageUrl} className="shadow-md" />
        </div>

        <div>
          <h1 className="text-3xl font-bold tracking-tight">{book.title}</h1>
          <p className="mt-1 text-slate-600">by <span className="font-medium text-slate-800">{authors}</span></p>
          <div className="mt-2"><Stars rating={book.avgRating} count={book.ratingCount} /></div>
          <div className="mt-3 flex flex-wrap gap-2">
            {book.categories.map((c) => (
              <Link key={c.id} to={`/books?category=${c.slug}`} className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-200">
                {c.name}
              </Link>
            ))}
          </div>

          <div className="mt-6" role="radiogroup" aria-label="Format">
            <div className="flex flex-wrap gap-2">
              {book.variants.map((v) => {
                const active = v.format === variant.format
                return (
                  <button
                    key={v.format}
                    type="button"
                    role="radio"
                    aria-checked={active}
                    onClick={() => setSelected(v.format)}
                    className={`rounded-lg border px-4 py-2 text-left text-sm ${active ? 'border-brand-700 bg-brand-50 ring-1 ring-brand-700' : 'border-slate-300 bg-white hover:border-slate-400'}`}
                  >
                    <span className="block font-medium">{FORMAT_LABELS[v.format]}</span>
                    <span className="text-slate-600">{formatPrice(v.sellingPrice)}</span>
                  </button>
                )
              })}
            </div>
          </div>

          <div className="mt-6 rounded-xl border border-slate-200 bg-white p-5">
            <div className="flex items-baseline gap-3">
              <span className="text-3xl font-bold">{formatPrice(variant.sellingPrice)}</span>
              {variant.discountPercent > 0 && (
                <>
                  <span className="text-slate-400 line-through">{formatPrice(variant.mrp)}</span>
                  <span className="font-semibold text-emerald-700">{variant.discountPercent}% off</span>
                </>
              )}
            </div>
            <p className={`mt-2 text-sm font-medium ${stock.tone}`}>{stock.text}</p>
            <p className="mt-4 text-sm text-slate-500">Adding to Your Stack and Bookmarks arrives in the next update.</p>
          </div>

          {book.description && (
            <section className="mt-8">
              <h2 className="text-lg font-semibold">About this book</h2>
              <p className="mt-2 whitespace-pre-line leading-relaxed text-slate-700">{book.description}</p>
            </section>
          )}

          <section className="mt-8">
            <h2 className="text-lg font-semibold">Details</h2>
            <dl className="mt-3 grid grid-cols-[140px_1fr] gap-y-2 text-sm">
              <Detail label="ISBN" value={variant.isbn} />
              <Detail label="Format" value={FORMAT_LABELS[variant.format]} />
              <Detail label="Pages" value={variant.pages} />
              <Detail label="Publisher" value={book.publisher} />
              <Detail label="Published" value={book.publishedYear} />
              <Detail label="Language" value={book.language} />
            </dl>
          </section>
        </div>
      </div>
    </div>
  )
}

function Detail({ label, value }) {
  if (value == null || value === '') return null
  return (
    <>
      <dt className="text-slate-500">{label}</dt>
      <dd className="text-slate-800">{value}</dd>
    </>
  )
}
