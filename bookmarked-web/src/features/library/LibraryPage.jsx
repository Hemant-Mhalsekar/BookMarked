import { useState } from 'react'
import { Link } from 'react-router-dom'
import { BRAND } from '../../brand'
import BookCover from '../../components/BookCover'
import { Alert, FullPageSpinner, Spinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { formatDateTime } from '../../utils/format'
import { fileUrl, progressPercent, progressText, useDownloadLinkMutation, useLibraryQuery } from './libraryApi'

/** /library — every e-book you own, most recently read first. */
export default function LibraryPage() {
  const { data: books, isLoading, error } = useLibraryQuery()
  const [message, setMessage] = useState(null)

  if (isLoading) return <FullPageSpinner />

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="text-2xl font-bold tracking-tight">{BRAND.library}</h1>
      <p className="text-sm text-slate-500">Your e-books, ready to read in the browser or download.</p>
      <div className="mt-4 space-y-3">
        {error && <Alert type="error">{parseApiError(error).message}</Alert>}
        {message && <Alert type={message.type}>{message.text}</Alert>}
      </div>

      {books?.length === 0 && (
        <div className="py-16 text-center">
          <p className="text-slate-600">No e-books yet. E-books you buy appear here instantly.</p>
          <Link to="/books?format=EBOOK" className="btn-primary mt-4 inline-flex">Browse e-books</Link>
        </div>
      )}

      <ul className="mt-6 grid grid-cols-2 gap-x-5 gap-y-8 sm:grid-cols-3 lg:grid-cols-5">
        {books?.map((b) => <LibraryCard key={b.variantId} book={b} onMessage={setMessage} />)}
      </ul>
    </div>
  )
}

function LibraryCard({ book, onMessage }) {
  const [getDownload, { isLoading }] = useDownloadLinkMutation()
  const progress = progressText(book.lastPage, book.totalPages)
  const percent = progressPercent(book.lastPage, book.totalPages)

  const download = async () => {
    onMessage(null)
    try {
      const link = await getDownload(book.variantId).unwrap()
      // a plain navigation lets the browser save the watermarked file itself
      window.location.assign(fileUrl(link.url))
      onMessage({ type: 'success', text: `Downloading “${book.title}”. ${link.downloadsLeft} ${link.downloadsLeft === 1 ? 'download' : 'downloads'} left for this book.` })
    } catch (e) {
      onMessage({ type: 'error', text: parseApiError(e).message })
    }
  }

  return (
    <li className="flex flex-col">
      <Link to={book.available ? `/library/read/${book.variantId}` : `/book/${book.slug}`} className="block">
        <BookCover title={book.title} author={book.authors.join(', ')} src={book.coverImageUrl} />
      </Link>
      <div className="mt-2 flex-1">
        <p className="line-clamp-2 text-sm font-semibold">{book.title}</p>
        <p className="truncate text-xs text-slate-500">{book.authors.join(', ')}</p>
        {progress ? (
          <>
            <div className="mt-2 h-1.5 rounded-full bg-slate-200" aria-hidden="true">
              <div className="h-1.5 rounded-full bg-brand-700" style={{ width: `${percent}%` }} />
            </div>
            <p className="mt-1 text-[11px] text-slate-500">{progress}</p>
          </>
        ) : (
          <p className="mt-2 text-[11px] text-slate-500">Bought {formatDateTime(book.purchasedAt)}</p>
        )}
      </div>
      {book.available ? (
        <div className="mt-2 flex gap-2">
          <Link to={`/library/read/${book.variantId}`} className="btn-primary flex-1 py-1.5 text-xs">
            {progress ? 'Continue' : 'Read'}
          </Link>
          <button type="button" className="btn-secondary py-1.5 text-xs" disabled={isLoading || book.downloadsLeft === 0}
                  title={book.downloadsLeft === 0 ? 'Download limit reached' : `${book.downloadsLeft} downloads left`}
                  onClick={download}>
            {isLoading ? <Spinner className="h-3 w-3" /> : 'PDF'}
          </button>
        </div>
      ) : (
        <p className="mt-2 text-xs text-amber-700">Preparing your file…</p>
      )}
    </li>
  )
}
