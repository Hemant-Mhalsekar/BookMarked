import { useSelector } from 'react-redux'
import { NavLink, Outlet } from 'react-router-dom'
import { BRAND } from '../../brand'
import { selectUser } from '../auth/authSlice'

const CUSTOMER_LINKS = [
  { to: '/account', label: 'Overview', end: true },
  { to: '/account/profile', label: 'Profile' },
  { to: '/orders', label: 'Orders' },
  { to: '/library', label: BRAND.library },
  { to: '/bookmarks', label: BRAND.wishlist },
  { to: '/account/addresses', label: 'Addresses' },
  { to: '/account/reviews', label: 'My reviews' },
  { to: '/notifications', label: 'Notifications' },
  { to: '/account/security', label: 'Security' },
]

const STAFF_LINKS = [
  { to: '/account/profile', label: 'Profile' },
  { to: '/notifications', label: 'Notifications' },
  { to: '/account/security', label: 'Security' },
]

/** Sidebar on desktop, a scrolling row of links on phones. */
export default function AccountLayout() {
  const user = useSelector(selectUser)
  const links = user?.accountType === 'STAFF' ? STAFF_LINKS : CUSTOMER_LINKS
  return (
    <div className="mx-auto grid max-w-6xl grid-cols-1 gap-6 px-4 py-6 md:grid-cols-[200px_1fr]">
      <aside>
        <p className="hidden px-3 text-xs font-semibold uppercase tracking-wider text-slate-500 md:block">My account</p>
        <nav className="-mx-4 flex gap-1 overflow-x-auto px-4 pb-1 md:mx-0 md:mt-2 md:flex-col md:px-0" aria-label="Account">
          {links.map((l) => (
            <NavLink key={l.to} to={l.to} end={l.end}
                     className={({ isActive }) => `whitespace-nowrap rounded-lg px-3 py-2 text-sm font-medium ${
                       isActive ? 'bg-brand-800 text-white' : 'text-slate-700 hover:bg-slate-100'}`}>
              {l.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="min-w-0 [&>div]:max-w-none [&>div]:px-0 [&>div]:py-0">
        <Outlet />
      </div>
    </div>
  )
}
