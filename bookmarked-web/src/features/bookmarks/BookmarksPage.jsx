import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { BRAND } from '../../brand'
import BookCover from '../../components/BookCover'
import { Alert, FullPageSpinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { FORMAT_LABELS, formatPrice } from '../../utils/format'
import { useStack } from '../stack/useStack'
import { useBookmarksQuery, useRemoveBookmarkMutation } from './bookmarksApi'

export default function BookmarksPage() {
  const { data: bookmarks, isLoading, error } = useBookmarksQuery()
  if (isLoading) return <FullPageSpinner />

  return (
    <div className="mx-auto max-w-5xl px-4 py-8">
      <h1 className="text-2xl font-bold tracking-tight">Your {BRAND.wishlist}</h1>
      <p className="text-sm text-slate-500">Books you saved for later.</p>
      {error && <div className="mt-4"><Alert type="error">{parseApiError(error).message}</Alert></div>}

      {bookmarks?.length === 0 ? (
        <div className="mt-10 rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center">
          <p className="font-medium">No bookmarks yet.</p>
          <p className="mt-1 text-sm text-slate-500">Tap the bookmark icon on any book to save it here.</p>
          <Link to="/books" className="btn-primary mt-5">Browse books</Link>
        </div>
      ) : (
        <ul className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2">
          {bookmarks?.map((b) => <BookmarkRow key={b.book.id} bookmark={b} />)}
        </ul>
      )}
    </div>
  )
}

function BookmarkRow({ bookmark }) {
  const { book, variants, available } = bookmark
  const purchasable = variants.filter((v) => v.stockStatus !== 'OUT_OF_STOCK')
  const [format, setFormat] = useState(purchasable[0]?.format ?? '')
  const [message, setMessage] = useState(null)
  const [remove] = useRemoveBookmarkMutation()
  const stack = useStack()
  const navigate = useNavigate()
  const selected = variants.find((v) => v.format === format)

  const moveToStack = async () => {
    setMessage(null)
    try {
      await stack.add(selected, 1)
      await remove(book.id).unwrap()
      navigate('/stack')
    } catch (e) {
      setMessage(parseApiError(e).message)
    }
  }

  return (
    <li className="flex gap-4 rounded-xl border border-slate-200 bg-white p-4">
      <Link to={`/book/${book.slug}`} className="w-20 shrink-0">
        <BookCover title={book.title} src={book.coverImageUrl} compact />
      </Link>
      <div className="min-w-0 flex-1 space-y-2">
        <div>
          <Link to={`/book/${book.slug}`} className="font-semibold hover:text-brand-700">{book.title}</Link>
          <p className="text-sm text-slate-500">{book.authors.join(', ')}</p>
        </div>
        {!available ? (
          <p className="text-sm text-slate-500">No longer available.</p>
        ) : purchasable.length === 0 ? (
          <p className="text-sm text-red-700">Out of stock in every format.</p>
        ) : (
          <div className="flex flex-wrap items-center gap-2">
            <select className="input w-auto py-1.5" value={format} onChange={(e) => setFormat(e.target.value)} aria-label="Format">
              {purchasable.map((v) => (
                <option key={v.format} value={v.format}>{FORMAT_LABELS[v.format]} · {formatPrice(v.sellingPrice)}</option>
              ))}
            </select>
            <button type="button" className="btn-primary py-1.5" onClick={moveToStack} disabled={!selected}>
              Move to {BRAND.cart}
            </button>
          </div>
        )}
        {message && <p className="text-sm text-red-700">{message}</p>}
        <button type="button" className="text-sm font-medium text-red-700 hover:underline" onClick={() => remove(book.id)}>
          Remove
        </button>
      </div>
    </li>
  )
}
