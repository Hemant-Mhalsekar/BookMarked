import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import AuthCard from '../../components/AuthCard'
import { Alert, PasswordChecklist, PasswordInput, SubmitButton } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { validatePasswordPair } from '../../utils/validation'
import { useAcceptInviteMutation } from './authApi'

/** /accept-invite?token=… — a new staff member chooses their first password. */
export default function AcceptInvitePage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [touched, setTouched] = useState(false)
  const [accept, { isLoading, isSuccess, error }] = useAcceptInviteMutation()

  const errors = touched ? validatePasswordPair(password, confirmPassword) : {}
  const apiError = parseApiError(error)

  if (!token) {
    return (
      <AuthCard title="Invite link missing">
        <Alert type="error">Open the link from your invite email to set up your staff account.</Alert>
      </AuthCard>
    )
  }

  if (isSuccess) {
    return (
      <AuthCard title="You're all set" subtitle="Your staff account is ready.">
        <Link to="/login" className="btn-primary w-full">Log in</Link>
      </AuthCard>
    )
  }

  return (
    <AuthCard title="Welcome to the BookMarked team" subtitle="Choose a password for your staff account.">
      <form className="space-y-4" noValidate onSubmit={(e) => {
        e.preventDefault()
        setTouched(true)
        if (Object.keys(validatePasswordPair(password, confirmPassword)).length) return
        accept({ token, newPassword: password })
      }}>
        {apiError && <Alert type="error">{apiError.message}</Alert>}
        <PasswordInput label="Password" autoComplete="new-password" value={password}
                       onChange={(e) => setPassword(e.target.value)} error={errors.password ?? apiError?.fieldErrors?.newPassword} />
        <PasswordChecklist password={password} />
        <PasswordInput label="Confirm password" autoComplete="new-password" value={confirmPassword}
                       onChange={(e) => setConfirmPassword(e.target.value)} error={errors.confirmPassword} />
        <SubmitButton loading={isLoading}>Activate my account</SubmitButton>
      </form>
    </AuthCard>
  )
}
