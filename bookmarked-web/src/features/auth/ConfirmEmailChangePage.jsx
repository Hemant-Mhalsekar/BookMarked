import { useEffect, useRef } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import AuthCard from '../../components/AuthCard'
import { Alert, FullPageSpinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { useConfirmEmailChangeMutation } from './authApi'

/** Opened from the link sent to the new address. Works logged in or out. */
export default function ConfirmEmailChangePage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [confirm, { isLoading, isSuccess, error, isUninitialized }] = useConfirmEmailChangeMutation()
  const sent = useRef(false)

  useEffect(() => {
    // StrictMode runs effects twice; the token works only once
    if (token && !sent.current) {
      sent.current = true
      confirm(token)
    }
  }, [token, confirm])

  if (token && (isLoading || isUninitialized)) return <FullPageSpinner />

  if (isSuccess) {
    return (
      <AuthCard title="Email changed" subtitle="You've been signed out everywhere. Log in with your new address.">
        <Link to="/login" className="btn-primary w-full">Log in</Link>
      </AuthCard>
    )
  }
  return (
    <AuthCard title="Confirm your new email" subtitle="We couldn't confirm this change.">
      <div className="space-y-4">
        <Alert type="error">{error ? parseApiError(error).message : 'Open the link from the email we sent to your new address.'}</Alert>
        <Link to="/account/profile" className="btn-secondary w-full">Back to your profile</Link>
      </div>
    </AuthCard>
  )
}
