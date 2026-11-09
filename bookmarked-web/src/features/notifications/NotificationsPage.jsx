import { useState } from 'react'
import { Link } from 'react-router-dom'
import { FullPageSpinner } from '../../components/ui'
import { formatDateTime } from '../../utils/format'
import { TYPE_TONE, useMarkAllReadMutation, useMarkReadMutation, useNotificationsQuery } from './notificationsApi'

export default function NotificationsPage() {
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [page, setPage] = useState(0)
  const { data, isLoading } = useNotificationsQuery({ unreadOnly, page, size: 20 })
  const [markRead] = useMarkReadMutation()
  const [markAllRead, { isLoading: marking }] = useMarkAllReadMutation()

  if (isLoading) return <FullPageSpinner />

  return (
    <div className="mx-auto max-w-3xl px-4 py-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight">Notifications</h1>
        <button type="button" className="btn-secondary" disabled={marking} onClick={() => markAllRead()}>
          Mark all read
        </button>
      </div>

      <div className="mt-4 flex gap-2" role="tablist" aria-label="Filter">
        {[[false, 'All'], [true, 'Unread']].map(([value, label]) => (
          <button key={label} type="button" role="tab" aria-selected={unreadOnly === value}
                  onClick={() => {
                    setUnreadOnly(value)
                    setPage(0)
                  }}
                  className={`rounded-full px-3 py-1 text-sm font-medium ${unreadOnly === value ? 'bg-brand-800 text-white' : 'bg-white text-slate-700 ring-1 ring-slate-200'}`}>
            {label}
          </button>
        ))}
      </div>

      {data?.content.length === 0 ? (
        <p className="py-16 text-center text-slate-500">{unreadOnly ? "You're all caught up." : 'No notifications yet.'}</p>
      ) : (
        <ul className="mt-4 divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white">
          {data?.content.map((n) => (
            <li key={n.id} className={`flex gap-3 p-4 ${n.read ? '' : 'bg-brand-50/50'}`}>
              <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${n.read ? 'bg-slate-300' : TYPE_TONE[n.type] ?? 'bg-brand-700'}`} aria-hidden="true" />
              <div className="min-w-0 flex-1 text-sm">
                <p className={n.read ? 'text-slate-700' : 'font-semibold text-slate-900'}>{n.title}</p>
                <p className="text-slate-600">{n.message}</p>
                <p className="mt-1 text-xs text-slate-400">{formatDateTime(n.createdAt)}</p>
              </div>
              <div className="flex shrink-0 flex-col items-end gap-1 text-xs">
                {n.link && (
                  <Link to={n.link} className="link" onClick={() => !n.read && markRead(n.id)}>Open</Link>
                )}
                {!n.read && <button type="button" className="text-slate-500 hover:underline" onClick={() => markRead(n.id)}>Mark read</button>}
              </div>
            </li>
          ))}
        </ul>
      )}

      {data && data.totalPages > 1 && (
        <nav className="mt-6 flex items-center justify-center gap-3 text-sm" aria-label="Pages">
          <button type="button" className="btn-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span>Page {page + 1} of {data.totalPages}</span>
          <button type="button" className="btn-secondary" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
        </nav>
      )}
    </div>
  )
}
