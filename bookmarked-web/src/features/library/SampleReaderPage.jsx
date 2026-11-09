import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import PdfReader from './PdfReader'
import { sampleUrl } from './libraryApi'

/** /read-sample/:variantId?book=slug&title=… — the free sample, no account needed. */
export default function SampleReaderPage() {
  const { variantId } = useParams()
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const slug = params.get('book')
  const title = params.get('title') || 'Sample'
  const back = () => (slug ? navigate(`/book/${slug}`) : navigate(-1))

  return (
    <PdfReader
      file={sampleUrl(variantId)}
      title={`${title} · sample`}
      onClose={back}
      banner={
        <div className="flex flex-wrap items-center justify-center gap-2 bg-amber-100 px-3 py-1.5 text-center text-xs text-amber-900">
          You&apos;re reading the first pages for free.
          {slug && <Link to={`/book/${slug}`} className="font-semibold underline">Get the full e-book</Link>}
        </div>
      }
    />
  )
}
