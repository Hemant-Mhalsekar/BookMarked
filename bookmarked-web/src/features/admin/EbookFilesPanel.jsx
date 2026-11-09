import { useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Spinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { formatDateTime } from '../../utils/format'
import { useEbookFileQuery, useUploadEbookMutation } from '../library/libraryApi'

const MAX_MB = 100

/** Upload the e-book PDF (a sample is cut automatically) and, optionally, a hand-made sample. */
export default function EbookFilesPanel({ variantId, canWrite }) {
  const { data: info, isLoading } = useEbookFileQuery(variantId)
  const [upload, { isLoading: uploading, originalArgs }] = useUploadEbookMutation()
  const [message, setMessage] = useState(null)
  const bookInput = useRef(null)
  const sampleInput = useRef(null)

  const send = async (file, sample) => {
    setMessage(null)
    if (!file) return
    if (file.type && file.type !== 'application/pdf') {
      setMessage({ type: 'error', text: 'Choose a PDF file.' })
      return
    }
    if (file.size > MAX_MB * 1024 * 1024) {
      setMessage({ type: 'error', text: `PDFs can be up to ${MAX_MB} MB.` })
      return
    }
    try {
      const res = await upload({ variantId, file, sample }).unwrap()
      setMessage({ type: 'success', text: sample
        ? `Sample replaced (${res.samplePages} pages).`
        : `Uploaded: ${res.pages} pages. A ${res.samplePages}-page sample was made from the start of the book.` })
    } catch (e) {
      setMessage({ type: 'error', text: parseApiError(e).message })
    } finally {
      if (bookInput.current) bookInput.current.value = ''
      if (sampleInput.current) sampleInput.current.value = ''
    }
  }

  const busy = (sample) => uploading && Boolean(originalArgs?.sample) === sample

  return (
    <section className="card space-y-3">
      <div>
        <h2 className="font-semibold">E-book file</h2>
        <p className="text-sm text-slate-500">
          PDF only, up to {MAX_MB} MB. It&apos;s stored privately: buyers read it through short-lived links, and downloads are
          stamped with the buyer&apos;s name and email.
        </p>
      </div>

      {isLoading ? (
        <Spinner className="h-5 w-5 text-brand-700" />
      ) : info?.uploaded ? (
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-4">
          <div><dt className="text-slate-500">Pages</dt><dd className="font-medium">{info.pages}</dd></div>
          <div><dt className="text-slate-500">Size</dt><dd className="font-medium">{(info.fileSize / 1024 / 1024).toFixed(1)} MB</dd></div>
          <div><dt className="text-slate-500">Sample</dt><dd className="font-medium">{info.hasSample ? `${info.samplePages} pages` : 'None'}</dd></div>
          <div><dt className="text-slate-500">Uploaded</dt><dd className="font-medium">{formatDateTime(info.uploadedAt)}</dd></div>
        </dl>
      ) : (
        <Alert type="warning">No file yet. Customers who buy this e-book will see “Preparing your file” until you upload it.</Alert>
      )}

      {message && <Alert type={message.type}>{message.text}</Alert>}

      {canWrite && (
        <div className="flex flex-wrap items-center gap-2">
          <input ref={bookInput} type="file" accept="application/pdf,.pdf" className="hidden" id={`ebook-${variantId}`}
                 onChange={(e) => send(e.target.files?.[0], false)} />
          <label htmlFor={`ebook-${variantId}`} className={`btn-primary cursor-pointer py-1.5 text-sm ${uploading ? 'pointer-events-none opacity-60' : ''}`}>
            {busy(false) && <Spinner className="h-4 w-4" />}
            {info?.uploaded ? 'Replace PDF' : 'Upload PDF'}
          </label>
          {info?.uploaded && (
            <>
              <input ref={sampleInput} type="file" accept="application/pdf,.pdf" className="hidden" id={`sample-${variantId}`}
                     onChange={(e) => send(e.target.files?.[0], true)} />
              <label htmlFor={`sample-${variantId}`} className={`btn-secondary cursor-pointer py-1.5 text-sm ${uploading ? 'pointer-events-none opacity-60' : ''}`}>
                {busy(true) && <Spinner className="h-4 w-4" />}
                Use my own sample
              </label>
            </>
          )}
          {info?.hasSample && (
            <Link to={`/read-sample/${variantId}`} target="_blank" className="link text-sm">Preview sample</Link>
          )}
        </div>
      )}
    </section>
  )
}
