import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { timeAgo } from '../../utils/format'
import {
  NOTIFICATION_POLL_MS,
  TYPE_TONE,
  useMarkAllReadMutation,
  useMarkReadMutation,
  useNotificationsQuery,
  useUnreadCountQuery,
} from './notificationsApi'

/** Navbar bell: unread badge, the latest 10, mark all read. */
export default function NotificationBell() {
  const [open, setOpen] = useState(false)
  const ref = useRef(null)
  const navigate = useNavigate()
  const { data: count } = useUnreadCountQuery(undefined, {
    pollingInterval: NOTIFICATION_POLL_MS,
    skipPollingIfUnfocused: true,
  })
  const { data: latest, isFetching } = useNotificationsQuery({ size: 10 }, { skip: !open })
  const [markRead] = useMarkReadMutation()
  const [markAllRead] = useMarkAllReadMutation()
  const unread = count?.unread ?? 0

  useEffect(() => {
    if (!open) return undefined
    const close = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false)
    }
    const onKey = (e) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const openItem = (n) => {
    setOpen(false)
    if (!n.read) markRead(n.id)
    if (n.link) navigate(n.link)
  }

  return (
    <div className="relative" ref={ref}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="relative rounded-md p-2 text-brand-100 hover:text-white"
        aria-label={unread ? `Notifications, ${unread} unread` : 'Notifications'}
        aria-haspopup="true"
        aria-expanded={open}
      >
        <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        {unread > 0 && (
          <span className="absolute right-0.5 top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-amber-400 px-1 text-[10px] font-bold text-slate-900">
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 z-40 mt-2 w-80 max-w-[calc(100vw-2rem)] overflow-hidden rounded-lg border border-slate-200 bg-white text-sm shadow-lg">
          <div className="flex items-center justify-between border-b border-slate-100 px-4 py-2.5">
            <span className="font-semibold text-slate-900">Notifications</span>
            {unread > 0 && (
              <button type="button" className="link text-xs" onClick={() => markAllRead()}>
                Mark all read
              </button>
            )}
          </div>
          <ul className="max-h-96 overflow-y-auto">
            {isFetching && !latest && <li className="px-4 py-6 text-center text-slate-500">Loading…</li>}
            {latest?.content.length === 0 && <li className="px-4 py-6 text-center text-slate-500">Nothing here yet.</li>}
            {latest?.content.map((n) => (
              <li key={n.id}>
                <button type="button" onClick={() => openItem(n)}
                        className={`flex w-full gap-3 px-4 py-3 text-left hover:bg-slate-50 ${n.read ? '' : 'bg-brand-50/60'}`}>
                  <span className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${n.read ? 'bg-slate-300' : TYPE_TONE[n.type] ?? 'bg-brand-700'}`} aria-hidden="true" />
                  <span className="min-w-0">
                    <span className={`block ${n.read ? 'text-slate-700' : 'font-medium text-slate-900'}`}>{n.title}</span>
                    <span className="line-clamp-2 block text-xs text-slate-500">{n.message}</span>
                    <span className="mt-0.5 block text-[11px] text-slate-400">{timeAgo(n.createdAt)}</span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
          <Link to="/notifications" onClick={() => setOpen(false)}
                className="block border-t border-slate-100 px-4 py-2.5 text-center text-xs font-medium text-brand-700 hover:bg-slate-50">
            See all notifications
          </Link>
        </div>
      )}
    </div>
  )
}
