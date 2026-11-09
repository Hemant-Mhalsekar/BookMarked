import { useState } from 'react'
import { useSelector } from 'react-redux'
import { Link, NavLink, useNavigate } from 'react-router-dom'
import { selectUser } from '../features/auth/authSlice'
import { useLogoutMutation } from '../features/auth/authApi'
import { BRAND } from '../brand'
import { useStack } from '../features/stack/useStack'
import NotificationBell from '../features/notifications/NotificationBell'
import Logo from './Logo'
import SearchBox from './SearchBox'

const navLink = ({ isActive }) =>
  `rounded-md px-3 py-2 text-sm font-medium ${isActive ? 'bg-white/15 text-white' : 'text-brand-100 hover:text-white'}`

export default function Navbar() {
  const user = useSelector(selectUser)
  const [logout, { isLoading }] = useLogoutMutation()
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const stack = useStack()

  const handleLogout = async () => {
    setOpen(false)
    try {
      await logout().unwrap()
    } catch {
      // the session is cleared locally either way
    }
    navigate('/login')
  }

  return (
    <header className="sticky top-0 z-30 bg-brand-800 shadow">
      <nav className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-4">
        <Link to="/" className="flex shrink-0 items-center gap-2 text-lg font-bold text-white">
          <Logo className="h-7 w-7" />
          <span className="leading-tight">
            BookMarked
            <span className="hidden text-[11px] font-normal text-brand-100 md:block">Books worth coming back to</span>
          </span>
        </Link>

        <SearchBox className="hidden max-w-md flex-1 md:block" />

        <div className="flex items-center gap-1">
          <NavLink to="/books" className={(a) => `${navLink(a)} hidden sm:inline-block`}>
            Browse
          </NavLink>
          {user && user.accountType !== 'STAFF' && (
            <NavLink to="/bookmarks" className={(a) => `${navLink(a)} hidden sm:inline-block`}>
              {BRAND.wishlist}
            </NavLink>
          )}
          {!stack.isStaff && (
            <NavLink to="/stack" className={navLink} aria-label={`${BRAND.cart}, ${stack.count} items`}>
              <span className="relative inline-flex items-center gap-1.5">
                <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
                  <path d="M4 7h16M5 12h14M6 17h12" strokeLinecap="round" />
                </svg>
                <span className="hidden lg:inline">{BRAND.cart}</span>
                {stack.count > 0 && (
                  <span className="absolute -right-2 -top-2 flex h-4 min-w-4 items-center justify-center rounded-full bg-amber-400 px-1 text-[10px] font-bold text-slate-900 lg:static lg:ml-0.5">
                    {stack.count}
                  </span>
                )}
              </span>
            </NavLink>
          )}
          {user?.accountType === 'STAFF' && (
            <NavLink to="/admin" className={navLink}>
              Admin
            </NavLink>
          )}

          {user && <NotificationBell />}

          {user ? (
            <div className="relative ml-1">
              <button
                type="button"
                onClick={() => setOpen((o) => !o)}
                className="flex items-center gap-2 rounded-full bg-white/10 py-1 pl-1 pr-1 text-sm sm:pr-3 font-medium text-white hover:bg-white/20"
                aria-haspopup="menu"
                aria-expanded={open}
              >
                <span className="flex h-7 w-7 items-center justify-center rounded-full bg-white text-xs font-bold text-brand-800">
                  {user.fullName?.[0]?.toUpperCase() ?? '?'}
                </span>
                <span className="hidden sm:inline">{user.fullName.split(' ')[0]}</span>
              </button>
              {open && (
                <div
                  role="menu"
                  className="absolute right-0 mt-2 w-52 overflow-hidden rounded-lg border border-slate-200 bg-white py-1 text-sm shadow-lg"
                  onMouseLeave={() => setOpen(false)}
                >
                  <div className="border-b border-slate-100 px-4 py-2 text-xs text-slate-500">
                    Signed in as
                    <div className="truncate font-medium text-slate-800">{user.email}</div>
                  </div>
                  <Link role="menuitem" to="/account" className="block px-4 py-2 hover:bg-slate-50" onClick={() => setOpen(false)}>
                    My account
                  </Link>
                  {user.accountType !== 'STAFF' && (
                    <>
                      <Link role="menuitem" to="/books" className="block px-4 py-2 hover:bg-slate-50 sm:hidden" onClick={() => setOpen(false)}>
                        Browse
                      </Link>
                      <Link role="menuitem" to="/bookmarks" className="block px-4 py-2 hover:bg-slate-50 sm:hidden" onClick={() => setOpen(false)}>
                        {BRAND.wishlist}
                      </Link>
                      <Link role="menuitem" to="/library" className="block px-4 py-2 hover:bg-slate-50" onClick={() => setOpen(false)}>
                        {BRAND.library}
                      </Link>
                      <Link role="menuitem" to="/orders" className="block px-4 py-2 hover:bg-slate-50" onClick={() => setOpen(false)}>
                        My orders
                      </Link>
                      <Link role="menuitem" to="/account/addresses" className="block px-4 py-2 hover:bg-slate-50" onClick={() => setOpen(false)}>
                        Saved addresses
                      </Link>
                    </>
                  )}
                  <Link role="menuitem" to="/notifications" className="block px-4 py-2 hover:bg-slate-50" onClick={() => setOpen(false)}>
                    Notifications
                  </Link>
                  <Link
                    role="menuitem"
                    to="/account/security"
                    className="block px-4 py-2 hover:bg-slate-50"
                    onClick={() => setOpen(false)}
                  >
                    Security & sessions
                  </Link>
                  <button
                    role="menuitem"
                    type="button"
                    onClick={handleLogout}
                    disabled={isLoading}
                    className="block w-full px-4 py-2 text-left text-red-700 hover:bg-red-50"
                  >
                    Log out
                  </button>
                </div>
              )}
            </div>
          ) : (
            <div className="ml-2 flex items-center gap-2">
              <Link to="/login" className="rounded-md px-3 py-2 text-sm font-medium text-white hover:bg-white/10">
                Log in
              </Link>
              <Link to="/signup" className="rounded-md bg-white px-3 py-2 text-sm font-semibold text-brand-800 hover:bg-brand-50">
                Sign up
              </Link>
            </div>
          )}
        </div>
      </nav>
    </header>
  )
}
