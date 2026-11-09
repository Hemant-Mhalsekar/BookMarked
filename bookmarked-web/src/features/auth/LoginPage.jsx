import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import AuthCard from '../../components/AuthCard'
import { Alert, PasswordInput, SubmitButton, TextInput } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { useLoginMutation } from './authApi'

export default function LoginPage() {
  const [form, setForm] = useState({ identifier: '', password: '' })
  const [login, { isLoading, error }] = useLoginMutation()
  const navigate = useNavigate()
  const location = useLocation()
  const apiError = parseApiError(error)

  const onChange = (e) => setForm((f) => ({ ...f, [e.target.name]: e.target.value }))

  const onSubmit = async (e) => {
    e.preventDefault()
    try {
      const data = await login({ identifier: form.identifier.trim(), password: form.password }).unwrap()
      const fallback = data.user.accountType === 'STAFF' ? '/admin' : '/'
      navigate(location.state?.from ? `${location.state.from.pathname}${location.state.from.search ?? ''}` : fallback, { replace: true })
    } catch {
      // shown below
    }
  }

  return (
    <AuthCard
      title="Welcome back"
      subtitle="Log in with your email or mobile number."
      footer={
        <>
          New to BookMarked? <Link to="/signup" className="link">Create an account</Link>
        </>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {apiError && (
          <Alert type={apiError.code === 'ACCOUNT_LOCKED' ? 'warning' : 'error'}>
            {apiError.message}
            {apiError.code === 'ACCOUNT_LOCKED' && (
              <>
                {' '}
                <Link to="/forgot-password" className="link">Reset password</Link>
              </>
            )}
          </Alert>
        )}
        <TextInput
          label="Email or mobile number"
          name="identifier"
          autoComplete="username"
          value={form.identifier}
          onChange={onChange}
          placeholder="you@example.com or 98765 43210"
          required
        />
        <div className="space-y-1">
          <PasswordInput
            name="password"
            autoComplete="current-password"
            value={form.password}
            onChange={onChange}
            required
          />
          <div className="text-right">
            <Link to="/forgot-password" className="link text-xs">Forgot password?</Link>
          </div>
        </div>
        <SubmitButton loading={isLoading} disabled={!form.identifier || !form.password}>
          Log in
        </SubmitButton>
      </form>
    </AuthCard>
  )
}
