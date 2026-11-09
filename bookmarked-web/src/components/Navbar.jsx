import { useState } from 'react'
import { useSelector } from 'react-redux'
import { Link, NavLink, useNavigate } from 'react-router-dom'
import { selectUser } from '../features/auth/authSlice'
import { useLogoutMutation } from '../features/auth/authApi'
import SearchBox from './SearchBox'

const navLink = ({ isActive }) =>
  `rounded-md px-3 py-2 text-sm font-medium ${isActive ? 'bg-white/15 text-white' : 'text-brand-100 hover:text-white'}`

export default function Navbar() {
  const user = useSelector(selectUser)
  const [logout, { isLoading }] = useLogoutMutation()
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()

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
          <img src="/favicon.svg" alt="" className="h-7 w-7" />
          <span className="leading-tight">
            BookMarked
            <span className="hidden text-[11px] font-normal text-brand-100 md:block">Books worth coming back to</span>
          </span>
        </Link>

        <SearchBox className="hidden max-w-md flex-1 md:block" />

        <div className="flex items-center gap-1">
          <NavLink to="/books" className={navLink}>
            Browse
          </NavLink>
          {user?.accountType === 'STAFF' && (
            <NavLink to="/admin" className={navLink}>
              Admin
            </NavLink>
          )}

          {user ? (
            <div className="relative ml-2">
              <button
                type="button"
                onClick={() => setOpen((o) => !o)}
                className="flex items-center gap-2 rounded-full bg-white/10 py-1 pl-1 pr-3 text-sm font-medium text-white hover:bg-white/20"
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
