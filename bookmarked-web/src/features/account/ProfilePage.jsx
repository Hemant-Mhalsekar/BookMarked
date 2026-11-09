import { useState } from 'react'
import { Alert, FullPageSpinner, PasswordInput, SubmitButton, TextInput } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { NAME_REGEX, PHONE_REGEX } from '../../utils/validation'
import { useResendVerificationMutation } from '../auth/authApi'
import { useChangePhoneMutation, useMeQuery, useUpdateNameMutation } from './accountApi'

export default function ProfilePage() {
  const { data: me, isLoading } = useMeQuery()
  if (isLoading || !me) return <FullPageSpinner />
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold tracking-tight">Profile</h1>
      <EmailCard me={me} />
      <NameForm key={`name-${me.fullName}`} me={me} />
      <PhoneForm key={`phone-${me.phone}`} me={me} />
    </div>
  )
}

function EmailCard({ me }) {
  const [resend, { isLoading, isSuccess }] = useResendVerificationMutation()
  return (
    <section className="card space-y-2">
      <h2 className="font-semibold">Email</h2>
      <p className="flex flex-wrap items-center gap-2 text-sm">
        <span className="font-medium">{me.email}</span>
        <span className={`rounded-full px-2 py-0.5 text-xs ${me.emailVerified ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-800'}`}>
          {me.emailVerified ? 'Verified' : 'Not verified'}
        </span>
        {me.accountType === 'STAFF' && (
          <span className="rounded-full bg-brand-50 px-2 py-0.5 text-xs text-brand-800">Staff · {me.role.replaceAll('_', ' ').toLowerCase()}</span>
        )}
      </p>
      {!me.emailVerified && (
        <p className="text-sm text-slate-600">
          {isSuccess ? 'A new link is on its way.' : (
            <button type="button" className="link" disabled={isLoading} onClick={() => resend(me.email)}>Resend the verification email</button>
          )}
        </p>
      )}
    </section>
  )
}

function NameForm({ me }) {
  const [name, setName] = useState(me.fullName)
  const [error, setError] = useState(null)
  const [done, setDone] = useState(false)
  const [update, { isLoading }] = useUpdateNameMutation()

  const submit = async (e) => {
    e.preventDefault()
    setDone(false)
    if (!NAME_REGEX.test(name.trim())) {
      setError('Enter your name (2–60 letters)')
      return
    }
    setError(null)
    try {
      await update(name.trim()).unwrap()
      setDone(true)
    } catch (err) {
      setError(parseApiError(err).message)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="card space-y-3">
      <h2 className="font-semibold">Name</h2>
      <TextInput label="Full name" autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} error={error} />
      {done && <Alert type="success">Name updated.</Alert>}
      <SubmitButton loading={isLoading} className="btn-primary" disabled={name.trim() === me.fullName}>Save name</SubmitButton>
    </form>
  )
}

function PhoneForm({ me }) {
  const [phone, setPhone] = useState(me.phone)
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState({})
  const [error, setError] = useState(null)
  const [done, setDone] = useState(false)
  const [change, { isLoading }] = useChangePhoneMutation()

  const submit = async (e) => {
    e.preventDefault()
    setDone(false)
    setError(null)
    const found = {}
    if (!PHONE_REGEX.test(phone.trim())) found.phone = 'Enter a valid 10-digit mobile number'
    if (!password) found.password = 'Enter your password to confirm'
    setErrors(found)
    if (Object.keys(found).length) return
    try {
      await change({ phone: phone.trim(), password }).unwrap()
      setPassword('')
      setDone(true)
    } catch (err) {
      const parsed = parseApiError(err)
      setErrors(parsed.fieldErrors)
      setError(parsed.message)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="card space-y-3">
      <div>
        <h2 className="font-semibold">Mobile number</h2>
        <p className="text-sm text-slate-500">You can also log in with this number. Changing it needs your password.</p>
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <TextInput label="Mobile number" inputMode="numeric" maxLength={10} autoComplete="tel-national" value={phone}
                   onChange={(e) => setPhone(e.target.value.replace(/\D/g, ''))} error={errors.phone} />
        <PasswordInput label="Current password" autoComplete="current-password" value={password}
                       onChange={(e) => setPassword(e.target.value)} error={errors.password} />
      </div>
      {error && <Alert type="error">{error}</Alert>}
      {done && <Alert type="success">Mobile number updated.</Alert>}
      <SubmitButton loading={isLoading} className="btn-primary" disabled={phone.trim() === me.phone}>Change number</SubmitButton>
    </form>
  )
}
