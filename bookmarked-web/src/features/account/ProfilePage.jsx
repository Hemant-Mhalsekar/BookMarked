import { useState } from 'react'
import { useDispatch } from 'react-redux'
import { useNavigate } from 'react-router-dom'
import { Alert, FullPageSpinner, PasswordInput, SubmitButton, TextInput } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { EMAIL_REGEX, NAME_REGEX, PHONE_REGEX } from '../../utils/validation'
import { useResendVerificationMutation } from '../auth/authApi'
import {
  endLocalSession,
  useCancelEmailChangeMutation,
  useChangePhoneMutation,
  useDeleteAccountMutation,
  useMeQuery,
  usePendingEmailQuery,
  useRequestEmailChangeMutation,
  useUpdateNameMutation,
} from './accountApi'

export default function ProfilePage() {
  const { data: me, isLoading } = useMeQuery()
  if (isLoading || !me) return <FullPageSpinner />
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold tracking-tight">Profile</h1>
      <EmailCard me={me} />
      <NameForm key={`name-${me.fullName}`} me={me} />
      <PhoneForm key={`phone-${me.phone}`} me={me} />
      {me.accountType === 'CUSTOMER' && <DeleteAccount />}
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
      <EmailChange me={me} />
    </section>
  )
}

/** A new email needs the password, then a click on the link sent to the new address. */
function EmailChange({ me }) {
  const { data: pending } = usePendingEmailQuery()
  const [cancel, { isLoading: cancelling }] = useCancelEmailChangeMutation()
  const [request, { isLoading }] = useRequestEmailChangeMutation()
  const [open, setOpen] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState({})
  const [error, setError] = useState(null)

  if (pending) {
    return (
      <div className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
        <p>
          Waiting for you to confirm <strong>{pending.newEmail}</strong>. Open the link we sent there; until then you
          keep logging in with {me.email}.
        </p>
        <button type="button" className="link mt-1" disabled={cancelling} onClick={() => cancel()}>Cancel the change</button>
      </div>
    )
  }
  if (!open) return <button type="button" className="link text-sm" onClick={() => setOpen(true)}>Change email</button>

  const submit = async (e) => {
    e.preventDefault()
    setError(null)
    const found = {}
    if (!EMAIL_REGEX.test(email.trim())) found.newEmail = 'Enter a valid email'
    else if (email.trim().toLowerCase() === me.email) found.newEmail = "That's already your email"
    if (!password) found.password = 'Enter your password to confirm'
    setErrors(found)
    if (Object.keys(found).length) return
    try {
      await request({ newEmail: email.trim(), password }).unwrap()
      setOpen(false)
      setPassword('')
    } catch (err) {
      const parsed = parseApiError(err)
      setErrors(parsed.fieldErrors)
      setError(parsed.message)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-3 border-t border-slate-100 pt-3">
      <p className="text-sm text-slate-500">
        We&apos;ll send a link to the new address. Nothing changes until you click it, and we&apos;ll let {me.email} know.
        After the change you&apos;ll be signed out everywhere.
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <TextInput label="New email" type="email" autoComplete="email" value={email}
                   onChange={(e) => setEmail(e.target.value)} error={errors.newEmail} />
        <PasswordInput label="Current password" autoComplete="current-password" value={password}
                       onChange={(e) => setPassword(e.target.value)} error={errors.password} />
      </div>
      {error && <Alert type="error">{error}</Alert>}
      <div className="flex gap-2">
        <SubmitButton loading={isLoading} className="btn-primary">Send confirmation link</SubmitButton>
        <button type="button" className="btn-secondary" onClick={() => setOpen(false)}>Cancel</button>
      </div>
    </form>
  )
}

/** Password plus typing DELETE. Refused (with the reason) while an order, return or refund is in progress. */
function DeleteAccount() {
  const navigate = useNavigate()
  const dispatch = useDispatch()
  const [open, setOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState(null)
  const [remove, { isLoading }] = useDeleteAccountMutation()

  const submit = async (e) => {
    e.preventDefault()
    setError(null)
    try {
      await remove({ password, confirmation }).unwrap()
      navigate('/goodbye', { replace: true })
      dispatch(endLocalSession())
    } catch (err) {
      setError(parseApiError(err).message)
    }
  }

  return (
    <section className="card space-y-3 border-red-200">
      <div>
        <h2 className="font-semibold text-red-800">Delete account</h2>
        <p className="text-sm text-slate-600">
          This removes your name, contact details, addresses, Your Stack and Bookmarks, and you lose access to My Library.
          Your reviews stay, shown as &ldquo;Former customer&rdquo;. Past orders and invoices are kept, as tax law requires.
          This can&apos;t be undone.
        </p>
      </div>
      {!open ? (
        <button type="button" className="btn border border-red-700 bg-white text-red-700 hover:bg-red-50" onClick={() => setOpen(true)}>
          Delete my account…
        </button>
      ) : (
        <form onSubmit={submit} noValidate className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <PasswordInput label="Password" autoComplete="current-password" value={password}
                           onChange={(e) => setPassword(e.target.value)} />
            <TextInput label="Type DELETE to confirm" autoComplete="off" value={confirmation}
                       onChange={(e) => setConfirmation(e.target.value)} />
          </div>
          {error && <Alert type="error">{error}</Alert>}
          <div className="flex gap-2">
            <SubmitButton loading={isLoading} disabled={!password || confirmation !== 'DELETE'}
                          className="btn border border-red-700 bg-red-700 text-white hover:bg-red-800 disabled:opacity-50">
              Permanently delete
            </SubmitButton>
            <button type="button" className="btn-secondary" onClick={() => setOpen(false)}>Keep my account</button>
          </div>
        </form>
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
