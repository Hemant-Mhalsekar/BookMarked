import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { BRAND } from '../../brand'
import { Stars } from '../../components/BookCard'
import BookCover from '../../components/BookCover'
import BookmarkButton from '../../components/BookmarkButton'
import BookRow from '../../components/BookRow'
import { Alert, FullPageSpinner, Spinner } from '../../components/ui'
import NotFoundPage from '../../pages/NotFoundPage'
import { parseApiError } from '../../utils/errors'
import { FORMAT_LABELS, formatPrice } from '../../utils/format'
import { useLibraryIdsQuery } from '../library/libraryApi'
import { useStack } from '../stack/useStack'
import { useBookQuery, useRelatedBooksQuery } from './catalogApi'
import { useRecentBooks, useTrackView } from './recentlyViewed'

function stockText(v) {
  switch (v.stockStatus) {
    case 'DIGITAL':
      return { text: 'Instant access in My Library after purchase', tone: 'text-brand-700' }
    case 'LOW_STOCK':
      return { text: `Only ${v.stockLeft} left in stock`, tone: 'text-amber-700' }
    case 'OUT_OF_STOCK':
      return { text: 'Out of stock', tone: 'text-red-700' }
    default:
      return { text: 'In stock', tone: 'text-emerald-700' }
  }
}

export default function BookDetailsPage() {
  const { slug } = useParams()
  const { data: book, isLoading, error } = useBookQuery(slug)

  if (isLoading) return <FullPageSpinner />
  if (error?.status === 404) return <NotFoundPage />
  if (error) {
    return <div className="mx-auto max-w-3xl px-4 py-10"><Alert type="error">{parseApiError(error).message}</Alert></div>
  }
  return <BookView key={book.id} book={book} />
}

function BookView({ book }) {
  useTrackView(book.id)
  const { data: related } = useRelatedBooksQuery(book.slug)
  const recent = useRecentBooks(book.id)

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
          <p className="mt-1 text-slate-600">
            by{' '}
            {book.authors.map((a, i) => (
              <span key={a.id}>
                {i > 0 && ', '}
                <Link to={`/books?author=${encodeURIComponent(a.name)}`} className="font-medium text-slate-800 hover:text-brand-700">{a.name}</Link>
              </span>
            ))}
          </p>
          <div className="mt-2"><Stars rating={book.avgRating} count={book.ratingCount} /></div>
          <div className="mt-3 flex flex-wrap gap-2">
            {book.categories.map((c) => (
              <Link key={c.id} to={`/books?category=${c.slug}`} className="rounded-full bg-slate-100 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-200">
                {c.name}
              </Link>
            ))}
          </div>

          <BuyBox book={book} />

          {book.description && <Description text={book.description} />}

          <section className="mt-8">
            <h2 className="text-lg font-semibold">Details</h2>
            <dl className="mt-3 grid grid-cols-[140px_1fr] gap-y-2 text-sm">
              <Detail label="Publisher" value={book.publisher} />
              <Detail label="Published" value={book.publishedYear} />
              <Detail label="Language" value={book.language} />
              {book.variants.map((v) => (
                <Detail key={v.format} label={`ISBN (${FORMAT_LABELS[v.format]})`} value={v.isbn} />
              ))}
              <Detail label="Pages" value={book.variants.find((v) => v.pages)?.pages} />
            </dl>
          </section>
        </div>
      </div>

      <div className="mt-14 space-y-12">
        <BookRow title={`More by ${book.authors[0]?.name ?? 'this author'}`} books={related?.moreByAuthor} />
        <BookRow title="You may also like" books={related?.similar} />
        <BookRow title="Recently viewed" books={recent} />
      </div>
    </div>
  )
}

function BuyBox({ book }) {
  const stack = useStack()
  const navigate = useNavigate()
  const firstAvailable = book.variants.find((v) => v.stockStatus !== 'OUT_OF_STOCK') ?? book.variants[0]
  const [format, setFormat] = useState(firstAvailable.format)
  const [quantity, setQuantity] = useState(1)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState(null)

  const variant = book.variants.find((v) => v.format === format) ?? firstAvailable
  const stock = stockText(variant)
  const soldOut = variant.stockStatus === 'OUT_OF_STOCK'
  const maxQty = variant.format === 'EBOOK' ? 1 : Math.min(10, variant.stockLeft ?? 10)
  const inStackQty = stack.view?.lines.find((l) => l.variantId === variant.id)?.quantity ?? 0
  const { data: ownedIds } = useLibraryIdsQuery(undefined, { skip: !stack.isCustomer })
  const owned = variant.format === 'EBOOK' && Boolean(ownedIds?.includes(variant.id))
  const sampleLink = `/read-sample/${variant.id}?book=${encodeURIComponent(book.slug)}&title=${encodeURIComponent(book.title)}`

  const add = async () => {
    setBusy(true)
    setMessage(null)
    try {
      const view = await stack.add(variant, quantity)
      setMessage({ type: 'success', text: view?.notice ?? `Added to ${BRAND.cart}.` })
    } catch (e) {
      setMessage({ type: 'error', text: parseApiError(e).message })
    } finally {
      setBusy(false)
    }
  }

  // Buy now skips Your Stack: straight to checkout with just this book
  const buyNow = () => {
    const target = `/checkout?buy=${variant.id}&qty=${variant.format === 'EBOOK' ? 1 : quantity}`
    if (stack.isGuest) navigate('/login', { state: { from: { pathname: '/checkout', search: target.slice('/checkout'.length) } } })
    else navigate(target)
  }

  return (
    <div className="mt-6 space-y-5">
      <div role="radiogroup" aria-label="Format" className="flex flex-wrap gap-2">
        {book.variants.map((v) => {
          const active = v.format === variant.format
          return (
            <button key={v.format} type="button" role="radio" aria-checked={active}
                    onClick={() => {
                      setFormat(v.format)
                      setQuantity(1)
                      setMessage(null)
                    }}
                    className={`rounded-lg border px-4 py-2 text-left text-sm ${active ? 'border-brand-700 bg-brand-50 ring-1 ring-brand-700' : 'border-slate-300 bg-white hover:border-slate-400'}`}>
              <span className="block font-medium">{FORMAT_LABELS[v.format]}</span>
              <span className={v.stockStatus === 'OUT_OF_STOCK' ? 'text-slate-400 line-through' : 'text-slate-600'}>
                {formatPrice(v.sellingPrice)}
              </span>
            </button>
          )
        })}
      </div>

      <div className="rounded-xl border border-slate-200 bg-white p-5">
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

        {owned ? (
          <div className="mt-4 flex flex-wrap items-center gap-3">
            <Link to={`/library/read/${variant.id}`} className="btn-primary">Read now</Link>
            <span className="text-sm text-emerald-700">In {BRAND.library}</span>
          </div>
        ) : stack.isStaff ? (
          <p className="mt-4 text-sm text-slate-500">You&apos;re signed in as staff, so shopping is turned off.</p>
        ) : (
          <div className="mt-4 flex flex-wrap items-center gap-3">
            {variant.format !== 'EBOOK' && !soldOut && (
              <label className="flex items-center gap-2 text-sm text-slate-600">
                Qty
                <select className="input w-auto py-2" value={quantity} onChange={(e) => setQuantity(Number(e.target.value))}>
                  {Array.from({ length: maxQty }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </label>
            )}
            <button type="button" className="btn-primary" disabled={soldOut || busy || (variant.format === 'EBOOK' && inStackQty > 0)}
                    onClick={add}>
              {busy && <Spinner className="h-4 w-4" />}
              {variant.format === 'EBOOK' && inStackQty > 0 ? `In ${BRAND.cart}` : `Add to ${BRAND.cart}`}
            </button>
            <button type="button" className="btn border border-amber-400 bg-amber-400 text-slate-900 hover:bg-amber-300"
                    disabled={soldOut || busy} onClick={buyNow}>
              Buy now
            </button>
            <BookmarkButton bookId={book.id} title={book.title} variant="button" />
          </div>
        )}
        {variant.format === 'EBOOK' && variant.sampleAvailable && !owned && (
          <p className="mt-3 text-sm">
            <Link to={sampleLink} className="link">Read a free sample</Link>
            {variant.pages ? <span className="text-slate-500"> · {variant.pages} pages</span> : null}
          </p>
        )}
        {inStackQty > 0 && variant.format !== 'EBOOK' && (
          <p className="mt-3 text-xs text-slate-500">{inStackQty} already in <Link to="/stack" className="link">{BRAND.cart}</Link>.</p>
        )}
        {message && (
          <div className="mt-3">
            <Alert type={message.type}>
              {message.text}{' '}
              {message.type === 'success' && <Link to="/stack" className="link">View {BRAND.cart}</Link>}
            </Alert>
          </div>
        )}
      </div>
    </div>
  )
}

function Description({ text }) {
  const [open, setOpen] = useState(false)
  const long = text.length > 600
  return (
    <section className="mt-8">
      <h2 className="text-lg font-semibold">About this book</h2>
      <p className={`mt-2 whitespace-pre-line leading-relaxed text-slate-700 ${!open && long ? 'line-clamp-6' : ''}`}>{text}</p>
      {long && (
        <button type="button" className="link mt-1 text-sm" onClick={() => setOpen((o) => !o)}>
          {open ? 'Show less' : 'Read more'}
        </button>
      )}
    </section>
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
