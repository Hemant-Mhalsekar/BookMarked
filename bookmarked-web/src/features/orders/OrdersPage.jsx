import { Link, useSearchParams } from 'react-router-dom'
import BookCover from '../../components/BookCover'
import { Alert, FullPageSpinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { formatDateTime, formatPrice } from '../../utils/format'
import { useOrdersQuery } from '../checkout/checkoutApi'
import { StatusBadge } from './OrderDetailPage'
import { ORDER_TABS } from './orderRules'

export default function OrdersPage() {
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const tab = ORDER_TABS.some((t) => t.key === params.get('tab')) ? params.get('tab') : 'ALL'
  const { data, isLoading, isFetching, error } = useOrdersQuery({ tab, page, size: 10 })
  const go = (next) => setParams(Object.fromEntries(Object.entries({ tab, page: String(page), ...next })
    .filter(([k, v]) => !(k === 'tab' && v === 'ALL') && !(k === 'page' && v === '0'))))

  if (isLoading) return <FullPageSpinner />

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      <h1 className="text-2xl font-bold tracking-tight">My orders</h1>
      <div className="mt-4 flex gap-2 overflow-x-auto" role="tablist" aria-label="Order status">
        {ORDER_TABS.map((t) => (
          <button key={t.key} type="button" role="tab" aria-selected={tab === t.key}
                  onClick={() => go({ tab: t.key, page: '0' })}
                  className={`whitespace-nowrap rounded-full px-3 py-1 text-sm font-medium ${tab === t.key ? 'bg-brand-800 text-white' : 'bg-white text-slate-700 ring-1 ring-slate-200 hover:ring-slate-300'}`}>
            {t.label}
          </button>
        ))}
      </div>
      {error && <div className="mt-4"><Alert type="error">{parseApiError(error).message}</Alert></div>}

      {data && data.content.length === 0 && (
        <div className="py-16 text-center">
          <p className="text-slate-600">{tab === 'ALL' ? "You haven't placed any orders yet." : 'No orders here.'}</p>
          <Link to="/books" className="btn-primary mt-4 inline-flex">Browse books</Link>
        </div>
      )}

      <ul className={`mt-6 space-y-3 ${isFetching ? 'opacity-60' : ''}`}>
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
                  onClick={() => go({ page: String(page - 1) })}>Previous</button>
          <span>Page {page + 1} of {data.totalPages}</span>
          <button type="button" className="btn-secondary" disabled={page + 1 >= data.totalPages}
                  onClick={() => go({ page: String(page + 1) })}>Next</button>
        </nav>
      )}
    </div>
  )
}
