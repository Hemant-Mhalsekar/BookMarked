import { useSelector } from 'react-redux'
import { NavLink, Outlet } from 'react-router-dom'
import { selectUser } from '../auth/authSlice'

// Each section is shown only to staff holding its permission; the API enforces the same rules.
const SECTIONS = [
  { to: '/admin', label: 'Dashboard', end: true },
  { to: '/admin/books', label: 'Books', permission: 'BOOK_READ' },
  { to: '/admin/categories', label: 'Categories', permission: 'BOOK_READ' },
]

export default function AdminLayout() {
  const user = useSelector(selectUser)
  const visible = SECTIONS.filter((s) => !s.permission || user.permissions.includes(s.permission))
  return (
    <div className="mx-auto grid max-w-7xl grid-cols-1 gap-6 px-4 py-6 md:grid-cols-[200px_1fr]">
      <aside>
        <p className="px-3 text-xs font-semibold uppercase tracking-wider text-slate-500">Admin</p>
        <nav className="mt-2 flex gap-1 overflow-x-auto md:flex-col" aria-label="Admin sections">
          {visible.map((s) => (
            <NavLink
              key={s.to}
              to={s.to}
              end={s.end}
              className={({ isActive }) =>
                `whitespace-nowrap rounded-lg px-3 py-2 text-sm font-medium ${isActive ? 'bg-brand-800 text-white' : 'text-slate-700 hover:bg-slate-100'}`
              }
            >
              {s.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="min-w-0">
        <Outlet />
      </div>
    </div>
  )
}
