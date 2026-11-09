import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import AuthCard from '../../components/AuthCard'
import { Alert, PasswordChecklist, PasswordInput, SubmitButton } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { validatePasswordPair } from '../../utils/validation'
import { useResetPasswordMutation } from './authApi'

export default function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [touched, setTouched] = useState(false)
  const [reset, { isLoading, isSuccess, error }] = useResetPasswordMutation()

  const errors = touched ? validatePasswordPair(password, confirmPassword) : {}
  const apiError = parseApiError(error)

  if (!token) {
    return (
      <AuthCard title="Reset link missing" footer={<Link to="/forgot-password" className="link">Request a new link</Link>}>
        <Alert type="error">Open the link from your reset email to choose a new password.</Alert>
      </AuthCard>
    )
  }

  if (isSuccess) {
    return (
      <AuthCard title="Password updated" subtitle="You've been signed out of all devices for safety.">
        <Link to="/login" className="btn-primary w-full">Log in</Link>
      </AuthCard>
    )
  }

  return (
    <AuthCard title="Choose a new password" footer={<Link to="/login" className="link">Back to log in</Link>}>
      <form
        className="space-y-4"
        noValidate
        onSubmit={(e) => {
          e.preventDefault()
          setTouched(true)
          if (Object.keys(validatePasswordPair(password, confirmPassword)).length) return
          reset({ token, newPassword: password })
        }}
      >
        {apiError && (
          <Alert type="error">
            {apiError.message}{' '}
            {apiError.code === 'TOKEN_INVALID' && <Link to="/forgot-password" className="link">Request a new link</Link>}
          </Alert>
        )}
        <PasswordInput label="New password" autoComplete="new-password" value={password}
                       onChange={(e) => setPassword(e.target.value)} error={errors.password ?? apiError?.fieldErrors?.newPassword} />
        <PasswordChecklist password={password} />
        <PasswordInput label="Confirm new password" autoComplete="new-password" value={confirmPassword}
                       onChange={(e) => setConfirmPassword(e.target.value)} error={errors.confirmPassword} />
        <SubmitButton loading={isLoading}>Update password</SubmitButton>
      </form>
    </AuthCard>
  )
}
