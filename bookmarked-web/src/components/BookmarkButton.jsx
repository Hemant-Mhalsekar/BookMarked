import { useSelector } from 'react-redux'
import { useLocation, useNavigate } from 'react-router-dom'
import { selectUser } from '../features/auth/authSlice'
import { useAddBookmarkMutation, useBookmarkIdsQuery, useRemoveBookmarkMutation } from '../features/bookmarks/bookmarksApi'

function Icon({ filled }) {
  return (
    <svg viewBox="0 0 24 24" className="h-5 w-5" aria-hidden="true" fill={filled ? 'currentColor' : 'none'}
         stroke="currentColor" strokeWidth="2" strokeLinejoin="round">
      <path d="M6 3.5h12a1 1 0 0 1 1 1V21l-7-4.6L5 21V4.5a1 1 0 0 1 1-1z" />
    </svg>
  )
}

/**
 * Bookmark toggle. Guests are sent to log in (and come back here); staff don't see it.
 * variant="overlay" sits on a book cover, variant="button" is a full button.
 */
export default function BookmarkButton({ bookId, title, variant = 'overlay' }) {
  const user = useSelector(selectUser)
  const navigate = useNavigate()
  const location = useLocation()
  const isCustomer = Boolean(user) && user.accountType !== 'STAFF'
  const { data: ids = [] } = useBookmarkIdsQuery(undefined, { skip: !isCustomer })
  const [add, addState] = useAddBookmarkMutation()
  const [remove, removeState] = useRemoveBookmarkMutation()

  if (user && !isCustomer) return null
  const saved = ids.includes(bookId)
  const busy = addState.isLoading || removeState.isLoading

  const onClick = (e) => {
    e.preventDefault()
    e.stopPropagation()
    if (!user) {
      navigate('/login', { state: { from: location } })
      return
    }
    if (saved) remove(bookId)
    else add(bookId)
  }

  const label = saved ? `Remove ${title} from Bookmarks` : `Bookmark ${title}`

  if (variant === 'button') {
    return (
      <button type="button" onClick={onClick} disabled={busy} aria-pressed={saved} aria-label={label}
              className={`btn border ${saved ? 'border-amber-300 bg-amber-50 text-amber-800' : 'border-slate-300 bg-white text-slate-700 hover:bg-slate-50'}`}>
        <Icon filled={saved} />
        {saved ? 'Bookmarked' : 'Bookmark'}
      </button>
    )
  }

  return (
    <button type="button" onClick={onClick} disabled={busy} aria-pressed={saved} aria-label={label} title={label}
            className={`flex h-9 w-9 items-center justify-center rounded-full bg-white/95 shadow ring-1 ring-black/5 transition hover:scale-105 ${saved ? 'text-amber-500' : 'text-slate-600 hover:text-slate-900'}`}>
      <Icon filled={saved} />
    </button>
  )
}
