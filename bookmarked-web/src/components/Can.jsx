import { useSelector } from 'react-redux'
import { selectHasPermission } from '../features/auth/authSlice'

/** Renders children only if the user holds the permission, e.g. <Can permission="BOOK_WRITE">. */
export default function Can({ permission, children, fallback = null }) {
  const allowed = useSelector(selectHasPermission(permission))
  return allowed ? children : fallback
}
