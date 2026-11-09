import { useSelector } from 'react-redux'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { selectAuthStatus, selectUser } from '../features/auth/authSlice'
import { FullPageSpinner } from './ui'

/**
 * Guards a group of routes. The backend enforces the same rules;
 * this only keeps people from landing on pages they can't use.
 */
export default function ProtectedRoute({ staffOnly = false, customerOnly = false, permission }) {
  const status = useSelector(selectAuthStatus)
  const user = useSelector(selectUser)
  const location = useLocation()

  if (status !== 'ready') return <FullPageSpinner />
  if (!user) return <Navigate to="/login" replace state={{ from: location }} />
  if (staffOnly && user.accountType !== 'STAFF') return <Navigate to="/" replace />
  if (customerOnly && user.accountType === 'STAFF') return <Navigate to="/admin" replace />
  if (permission && !user.permissions?.includes(permission)) return <Navigate to="/admin" replace />
  return <Outlet />
}

/** Pages like Login make no sense when already signed in. */
export function GuestOnlyRoute() {
  const status = useSelector(selectAuthStatus)
  const user = useSelector(selectUser)
  const location = useLocation()
  if (status !== 'ready') return <FullPageSpinner />
  if (user) {
    const fallback = user.accountType === 'STAFF' ? '/admin' : '/'
    return <Navigate to={location.state?.from?.pathname ?? fallback} replace />
  }
  return <Outlet />
}
