import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { BRAND } from '../../brand'
import { Alert, FullPageSpinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { fileUrl, useLibraryBookQuery, useReadLinkMutation, useSaveProgressMutation } from './libraryApi'
import PdfReader from './PdfReader'

/** /library/read/:variantId — opens the book where you left off and saves your place as you read. */
export default function ReaderPage() {
  const { variantId } = useParams()
  const navigate = useNavigate()
  const { data: book, error: bookError } = useLibraryBookQuery(variantId)
  const [getLink] = useReadLinkMutation()
  const [saveProgress] = useSaveProgressMutation()
  const [url, setUrl] = useState(null)
  const [linkError, setLinkError] = useState(null)
  const timer = useRef(null)

  useEffect(() => {
    let cancelled = false
    getLink(variantId).unwrap()
      .then((res) => !cancelled && setUrl(fileUrl(res.url)))
      .catch((e) => !cancelled && setLinkError(parseApiError(e).message))
    return () => {
      cancelled = true
    }
  }, [variantId, getLink])

  // Save the page a moment after it stops changing (not on every key press)
  const onPage = useCallback((page, totalPages) => {
    clearTimeout(timer.current)
    timer.current = setTimeout(() => saveProgress({ variantId, page, totalPages }), 1000)
  }, [variantId, saveProgress])
  useEffect(() => () => clearTimeout(timer.current), [])

  const error = linkError ?? (bookError ? parseApiError(bookError).message : null)
  if (error) {
    return (
      <div className="mx-auto max-w-lg px-4 py-16">
        <Alert type="error">{error}</Alert>
        <button type="button" className="btn-secondary mt-4" onClick={() => navigate('/library')}>Back to {BRAND.library}</button>
      </div>
    )
  }
  if (!book || !url) return <FullPageSpinner />

  return (
    <PdfReader
      file={url}
      title={book.title}
      initialPage={book.lastPage ?? 1}
      onPage={onPage}
      onClose={() => navigate('/library')}
    />
  )
}
