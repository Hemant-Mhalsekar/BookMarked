import { useState } from 'react'
import { Link } from 'react-router-dom'
import AuthCard from '../../components/AuthCard'
import { Alert, PasswordChecklist, PasswordInput, SubmitButton, TextInput } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { validateSignup } from '../../utils/validation'
import { useRegisterMutation, useResendVerificationMutation } from './authApi'

const empty = { fullName: '', email: '', phone: '', password: '', confirmPassword: '' }

export default function SignupPage() {
  const [form, setForm] = useState(empty)
  const [touched, setTouched] = useState(false)
  const [registeredEmail, setRegisteredEmail] = useState(null)
  const [register, { isLoading, error }] = useRegisterMutation()
  const apiError = parseApiError(error)

  const clientErrors = touched ? validateSignup(form) : {}
  const errors = { ...apiError?.fieldErrors, ...clientErrors }
  if (apiError?.code === 'EMAIL_TAKEN') errors.email = apiError.message
  if (apiError?.code === 'PHONE_TAKEN') errors.phone = apiError.message

  const onChange = (e) => {
    const { name, value } = e.target
    setForm((f) => ({ ...f, [name]: name === 'phone' ? value.replace(/\D/g, '').slice(0, 10) : value }))
  }

  const onSubmit = async (e) => {
    e.preventDefault()
    setTouched(true)
    if (Object.keys(validateSignup(form)).length > 0) return
    try {
      await register({
        fullName: form.fullName.trim(),
        email: form.email.trim(),
        phone: form.phone.trim(),
        password: form.password,
      }).unwrap()
      setRegisteredEmail(form.email.trim())
    } catch {
      // shown inline
    }
  }

  if (registeredEmail) return <CheckYourEmail email={registeredEmail} />

  const showBanner = apiError && !['EMAIL_TAKEN', 'PHONE_TAKEN', 'VALIDATION_FAILED'].includes(apiError.code)

  return (
    <AuthCard
      title="Create your account"
      subtitle="Buy paperbacks, hardcovers and e-books."
      footer={
        <>
          Already have an account? <Link to="/login" className="link">Log in</Link>
        </>
      }
    >
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        {showBanner && <Alert type="error">{apiError.message}</Alert>}
        <TextInput label="Full name" name="fullName" autoComplete="name" value={form.fullName}
                   onChange={onChange} error={errors.fullName} required />
        <TextInput label="Email" name="email" type="email" autoComplete="email" value={form.email}
                   onChange={onChange} error={errors.email} required />
        <div className="space-y-1.5">
          <label htmlFor="phone" className="block text-sm font-medium text-slate-700">Mobile number</label>
          <div className="flex">
            <span className="inline-flex items-center rounded-l-lg border border-r-0 border-slate-300 bg-slate-50 px-3 text-sm text-slate-600">
              +91
            </span>
            <input id="phone" name="phone" inputMode="numeric" autoComplete="tel-national"
                   className={`input rounded-l-none ${errors.phone ? 'input-error' : ''}`}
                   value={form.phone} onChange={onChange} placeholder="9876543210"
                   aria-invalid={Boolean(errors.phone)} required />
          </div>
          {errors.phone && <p className="text-xs text-red-600">{errors.phone}</p>}
        </div>
        <PasswordInput name="password" autoComplete="new-password" value={form.password}
                       onChange={onChange} error={errors.password} required />
        <PasswordChecklist password={form.password} />
        <PasswordInput label="Confirm password" name="confirmPassword" autoComplete="new-password"
                       value={form.confirmPassword} onChange={onChange} error={errors.confirmPassword} required />
        <SubmitButton loading={isLoading}>Create account</SubmitButton>
      </form>
    </AuthCard>
  )
}

function CheckYourEmail({ email }) {
  const [resend, { isLoading, isSuccess }] = useResendVerificationMutation()
  return (
    <AuthCard
      title="Check your email"
      subtitle={`We sent a verification link to ${email}.`}
      footer={<Link to="/login" className="link">Continue to log in</Link>}
    >
      <div className="space-y-4 text-sm text-slate-600">
        <p>Click the link in the email to verify your address. You can log in and browse right away; some features
          (like Cash on Delivery) need a verified email.</p>
        {isSuccess && <Alert type="success">A new link is on its way.</Alert>}
        <button type="button" className="btn-secondary w-full" disabled={isLoading || isSuccess}
                onClick={() => resend(email)}>
          Resend verification email
        </button>
      </div>
    </AuthCard>
  )
}
