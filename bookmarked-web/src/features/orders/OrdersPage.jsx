import { Link, useSearchParams } from 'react-router-dom'
import BookCover from '../../components/BookCover'
import { Alert, FullPageSpinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { formatDateTime, formatPrice } from '../../utils/format'
import { useOrdersQuery } from '../checkout/checkoutApi'
import { StatusBadge } from './OrderDetailPage'

export default function OrdersPage() {
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const { data, isLoading, error } = useOrdersQuery({ page, size: 10 })

  if (isLoading) return <FullPageSpinner />

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      <h1 className="text-2xl font-bold tracking-tight">My orders</h1>
      {error && <div className="mt-4"><Alert type="error">{parseApiError(error).message}</Alert></div>}

      {data && data.content.length === 0 && (
        <div className="py-16 text-center">
          <p className="text-slate-600">You haven&apos;t placed any orders yet.</p>
          <Link to="/books" className="btn-primary mt-4 inline-flex">Browse books</Link>
        </div>
      )}

      <ul className="mt-6 space-y-3">
        {data?.content.map((o) => (
          <li key={o.orderNumber}>
            <Link to={`/orders/${o.orderNumber}`}
                  className="flex flex-wrap items-center gap-4 rounded-xl border border-slate-200 bg-white p-4 hover:border-brand-700">
              <div className="flex -space-x-3">
                {o.items.slice(0, 3).map((i, idx) => (
                  <div key={idx} className="w-10 rounded ring-2 ring-white">
                    <BookCover title={i.title} src={i.coverImageUrl} compact />
                  </div>
                ))}
              </div>
              <div className="min-w-0 flex-1 text-sm">
                <p className="font-semibold">{o.orderNumber}</p>
                <p className="truncate text-slate-600">
                  {o.items[0]?.title}
                  {o.items.length > 1 ? ` and ${o.items.length - 1} more` : ''} · {o.itemCount} {o.itemCount === 1 ? 'item' : 'items'}
                </p>
                <p className="text-xs text-slate-500">{formatDateTime(o.createdAt)}</p>
              </div>
              <div className="text-right">
                <p className="font-semibold">{formatPrice(o.total)}</p>
                <StatusBadge status={o.status} />
                {o.awaitingPayment && <p className="mt-1 text-xs font-medium text-amber-700">Complete payment →</p>}
              </div>
            </Link>
          </li>
        ))}
      </ul>

      {data && data.totalPages > 1 && (
        <nav className="mt-6 flex items-center justify-center gap-3 text-sm" aria-label="Pages">
          <button type="button" className="btn-secondary" disabled={page === 0}
                  onClick={() => setParams({ page: String(page - 1) })}>Previous</button>
          <span>Page {page + 1} of {data.totalPages}</span>
          <button type="button" className="btn-secondary" disabled={page + 1 >= data.totalPages}
                  onClick={() => setParams({ page: String(page + 1) })}>Next</button>
        </nav>
      )}
    </div>
  )
}
