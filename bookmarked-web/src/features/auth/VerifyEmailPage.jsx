import { useEffect, useRef, useState } from 'react'
import { useSelector } from 'react-redux'
import { Link, useSearchParams } from 'react-router-dom'
import AuthCard from '../../components/AuthCard'
import { Alert, FullPageSpinner, SubmitButton, TextInput } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { selectUser } from './authSlice'
import { useResendVerificationMutation, useVerifyEmailMutation } from './authApi'

export default function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const user = useSelector(selectUser)
  const [verify, { isLoading, isSuccess, error, isUninitialized }] = useVerifyEmailMutation()
  const sent = useRef(false)

  useEffect(() => {
    // Guard against React StrictMode running the effect twice: the token is single-use.
    if (token && !sent.current) {
      sent.current = true
      verify(token)
    }
  }, [token, verify])

  if (token && (isLoading || isUninitialized)) return <FullPageSpinner />

  if (isSuccess) {
    return (
      <AuthCard title="Email verified" subtitle="Thanks for confirming your email address.">
        <Link to={user ? '/' : '/login'} className="btn-primary w-full">
          {user ? 'Start browsing' : 'Log in'}
        </Link>
      </AuthCard>
    )
  }

  return (
    <AuthCard title="Verify your email" subtitle="This link is invalid or has expired.">
      <div className="space-y-4">
        {error && <Alert type="error">{parseApiError(error).message}</Alert>}
        {!token && <Alert type="info">Open the link from your verification email, or request a new one.</Alert>}
        <ResendForm defaultEmail={user?.email ?? ''} />
      </div>
    </AuthCard>
  )
}

function ResendForm({ defaultEmail }) {
  const [email, setEmail] = useState(defaultEmail)
  const [resend, { isLoading, isSuccess }] = useResendVerificationMutation()
  if (isSuccess) return <Alert type="success">If that account needs verifying, a new link is on its way.</Alert>
  return (
    <form
      className="space-y-3"
      onSubmit={(e) => {
        e.preventDefault()
        resend(email.trim())
      }}
    >
      <TextInput label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      <SubmitButton loading={isLoading} disabled={!email}>Send a new link</SubmitButton>
    </form>
  )
}
