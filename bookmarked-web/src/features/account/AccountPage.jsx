import { useSelector } from 'react-redux'
import { Link, Navigate } from 'react-router-dom'
import { BRAND } from '../../brand'
import BookCover from '../../components/BookCover'
import { FullPageSpinner } from '../../components/ui'
import { formatDateTime, formatPrice } from '../../utils/format'
import { selectUser } from '../auth/authSlice'
import { progressPercent, progressText } from '../library/libraryApi'
import { StatusBadge } from '../orders/OrderDetailPage'
import { useOverviewQuery } from './accountApi'

/** /account — the dashboard's first screen (staff go to their profile). */
export default function AccountPage() {
  const user = useSelector(selectUser)
  if (user?.accountType === 'STAFF') return <Navigate to="/account/profile" replace />
  return <Overview />
}

function Overview() {
  const { data, isLoading } = useOverviewQuery(undefined, { refetchOnMountOrArgChange: true })
  if (isLoading || !data) return <FullPageSpinner />
  const { stats } = data

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Hi, {data.firstName}</h1>
        <p className="text-sm text-slate-600">Here&apos;s what&apos;s happening with your books.</p>
      </div>

      {data.pending.length > 0 && (
        <section aria-labelledby="todo" className="rounded-xl border border-amber-200 bg-amber-50 p-4">
          <h2 id="todo" className="text-sm font-semibold text-amber-900">Things to do</h2>
          <ul className="mt-2 space-y-1.5 text-sm">
            {data.pending.map((p, i) => (
              <li key={i} className="flex items-start gap-2">
                <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-amber-500" aria-hidden="true" />
                <Link to={p.link} className="text-amber-900 hover:underline">{p.text}</Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="grid grid-cols-2 gap-3 sm:grid-cols-4" aria-label="Your numbers">
        <Stat to="/orders" label="Orders" value={stats.orders} />
        <Stat to="/library" label={BRAND.library} value={stats.library} />
        <Stat to="/bookmarks" label={BRAND.wishlist} value={stats.bookmarks} />
        <Stat to="/account/reviews" label="Reviews" value={stats.reviews} />
      </section>

      {data.continueReading.length > 0 && (
        <section>
          <div className="flex items-baseline justify-between">
            <h2 className="text-lg font-semibold">Continue reading</h2>
            <Link to="/library" className="link text-sm">{BRAND.library}</Link>
          </div>
          <ul className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
            {data.continueReading.map((b) => (
              <li key={b.variantId}>
                <Link to={`/library/read/${b.variantId}`} className="flex gap-3 rounded-xl border border-slate-200 bg-white p-3 hover:border-brand-700">
                  <div className="w-12 shrink-0"><BookCover title={b.title} src={b.coverImageUrl} compact /></div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold">{b.title}</p>
                    <p className="truncate text-xs text-slate-500">{b.authors.join(', ')}</p>
                    <div className="mt-2 h-1.5 rounded-full bg-slate-200" aria-hidden="true">
                      <div className="h-1.5 rounded-full bg-brand-700" style={{ width: `${progressPercent(b.lastPage, b.totalPages)}%` }} />
                    </div>
                    <p className="mt-1 text-[11px] text-slate-500">{progressText(b.lastPage, b.totalPages)}</p>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section>
        <div className="flex items-baseline justify-between">
          <h2 className="text-lg font-semibold">Recent orders</h2>
          <Link to="/orders" className="link text-sm">All orders</Link>
        </div>
        {data.recentOrders.length === 0 ? (
          <p className="mt-3 text-sm text-slate-500">No orders yet. <Link to="/books" className="link">Find a book</Link></p>
        ) : (
          <ul className="mt-3 divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white">
            {data.recentOrders.map((o) => (
              <li key={o.orderNumber}>
                <Link to={`/orders/${o.orderNumber}`} className="flex flex-wrap items-center gap-3 px-4 py-3 text-sm hover:bg-slate-50">
                  <span className="font-medium">{o.orderNumber}</span>
                  <span className="text-slate-500">{formatDateTime(o.createdAt)}</span>
                  <span className="ml-auto font-medium">{formatPrice(o.total)}</span>
                  <StatusBadge status={o.status} />
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

function Stat({ to, label, value }) {
  return (
    <Link to={to} className="rounded-xl border border-slate-200 bg-white p-4 hover:border-brand-700">
      <p className="text-2xl font-bold">{value}</p>
      <p className="text-sm text-slate-500">{label}</p>
    </Link>
  )
}
