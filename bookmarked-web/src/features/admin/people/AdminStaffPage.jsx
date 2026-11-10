import { useState } from 'react'
import { Alert, Spinner, SubmitButton, TextInput } from '../../../components/ui'
import { parseApiError } from '../../../utils/errors'
import { timeAgo } from '../../../utils/format'
import { EMAIL_REGEX, NAME_REGEX, PHONE_REGEX } from '../../../utils/validation'
import {
  roleLabel,
  STAFF_ROLES,
  useChangeStaffRoleMutation,
  useCreateStaffMutation,
  useStaffActionMutation,
  useStaffQuery,
} from './peopleApi'

/** Staff accounts (STAFF_MANAGE): add people, change roles, block, sign out. */
export default function AdminStaffPage() {
  const { data: staff, isLoading, error } = useStaffQuery()
  const [adding, setAdding] = useState(false)
  const [message, setMessage] = useState(null)

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Staff</h1>
          <p className="text-sm text-slate-500">New staff get an email to set their own password. Role changes and blocks sign them out at once.</p>
        </div>
        {!adding && <button type="button" className="btn-primary" onClick={() => setAdding(true)}>Add staff member</button>}
      </div>

      <div className="mt-4 space-y-3">
        {error && <Alert type="error">{parseApiError(error).message}</Alert>}
        {message && <Alert type={message.type}>{message.text}</Alert>}
        {adding && <AddStaff onClose={() => setAdding(false)} onDone={(text) => {
          setAdding(false)
          setMessage({ type: 'success', text })
        }} />}
      </div>

      <div className="mt-4 overflow-x-auto rounded-xl border border-slate-200 bg-white">
        <table className="w-full min-w-[760px] text-left text-sm">
          <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">Person</th>
              <th className="px-4 py-3 font-medium">Role</th>
              <th className="px-4 py-3 font-medium">Status</th>
              <th className="px-4 py-3 font-medium">Last login</th>
              <th className="px-4 py-3" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {isLoading && <tr><td colSpan={5} className="py-10"><Spinner className="mx-auto h-5 w-5" /></td></tr>}
            {staff?.map((s) => <StaffRow key={s.id} s={s} onMessage={setMessage} />)}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function StaffRow({ s, onMessage }) {
  const [act, { isLoading }] = useStaffActionMutation()
  const [changeRole, { isLoading: changing }] = useChangeStaffRoleMutation()

  const run = async (promise, text) => {
    onMessage(null)
    try {
      await promise
      onMessage({ type: 'success', text })
    } catch (e) {
      onMessage({ type: 'error', text: parseApiError(e).message })
    }
  }

  return (
    <tr className={s.enabled ? '' : 'bg-slate-50 text-slate-500'}>
      <td className="px-4 py-3">
        <p className="font-medium">{s.fullName}{s.you && <span className="ml-2 text-xs font-normal text-slate-500">(you)</span>}</p>
        <p className="text-xs text-slate-500">{s.email} · {s.phone}</p>
      </td>
      <td className="px-4 py-3">
        {s.you ? roleLabel(s.role) : (
          <select className="input w-auto py-1 text-sm" value={s.role} disabled={changing}
                  aria-label={`Role of ${s.fullName}`}
                  onChange={(e) => run(changeRole({ id: s.id, role: e.target.value }).unwrap(),
                    `${s.fullName} is now ${roleLabel(e.target.value)}. They've been signed out to pick up the change.`)}>
            {STAFF_ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
          </select>
        )}
      </td>
      <td className="px-4 py-3">
        {!s.enabled ? <span className="rounded-full bg-red-50 px-2 py-0.5 text-xs font-medium text-red-700">Blocked</span>
          : s.invitePending ? <span className="rounded-full bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-800">Invite sent</span>
            : <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700">Active</span>}
      </td>
      <td className="px-4 py-3 text-xs">{s.lastLoginAt ? timeAgo(s.lastLoginAt) : 'Never'}</td>
      <td className="px-4 py-3 text-right">
        {!s.you && (
          <div className="flex justify-end gap-2 text-xs">
            {s.invitePending && (
              <button type="button" className="link" disabled={isLoading}
                      onClick={() => run(act({ id: s.id, action: 'resend-invite' }).unwrap(), `Invite sent again to ${s.email}.`)}>Resend invite</button>
            )}
            {s.enabled && (
              <button type="button" className="link" disabled={isLoading}
                      onClick={() => run(act({ id: s.id, action: 'sign-out' }).unwrap(), `${s.fullName} has been signed out everywhere.`)}>Sign out</button>
            )}
            <button type="button" className={s.enabled ? 'font-medium text-red-700 hover:underline' : 'link'} disabled={isLoading}
                    onClick={() => run(act({ id: s.id, action: s.enabled ? 'block' : 'unblock' }).unwrap(),
                      s.enabled ? `${s.fullName} is blocked and signed out.` : `${s.fullName} can log in again.`)}>
              {s.enabled ? 'Block' : 'Unblock'}
            </button>
          </div>
        )}
      </td>
    </tr>
  )
}

function AddStaff({ onClose, onDone }) {
  const [form, setForm] = useState({ fullName: '', email: '', phone: '', role: 'ORDER_MANAGER' })
  const [errors, setErrors] = useState({})
  const [error, setError] = useState(null)
  const [create, { isLoading }] = useCreateStaffMutation()
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }))

  const submit = async (e) => {
    e.preventDefault()
    setError(null)
    const found = {}
    if (!NAME_REGEX.test(form.fullName.trim())) found.fullName = 'Enter their name (2–60 letters)'
    if (!EMAIL_REGEX.test(form.email.trim())) found.email = 'Enter a valid email'
    if (!PHONE_REGEX.test(form.phone.trim())) found.phone = 'Enter a valid 10-digit mobile number'
    setErrors(found)
    if (Object.keys(found).length) return
    try {
      const s = await create({ ...form, fullName: form.fullName.trim(), email: form.email.trim(), phone: form.phone.trim() }).unwrap()
      onDone(`${s.fullName} added as ${roleLabel(s.role)}. An invite is on its way to ${s.email}.`)
    } catch (err) {
      const parsed = parseApiError(err)
      setErrors(parsed.fieldErrors)
      setError(parsed.message)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="card space-y-4">
      <h2 className="font-semibold">Add a staff member</h2>
      {error && <Alert type="error">{error}</Alert>}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <TextInput label="Full name" value={form.fullName} onChange={set('fullName')} error={errors.fullName} />
        <TextInput label="Work email" type="email" value={form.email} onChange={set('email')} error={errors.email} />
        <TextInput label="Mobile" inputMode="numeric" maxLength={10} value={form.phone}
                   onChange={(e) => setForm((f) => ({ ...f, phone: e.target.value.replace(/\D/g, '') }))} error={errors.phone} />
      </div>
      <fieldset className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        <legend className="mb-1 text-sm font-medium text-slate-700">Role</legend>
        {STAFF_ROLES.map((r) => (
          <label key={r.value} className={`flex cursor-pointer gap-2 rounded-lg border p-3 text-sm ${form.role === r.value ? 'border-brand-700 bg-brand-50' : 'border-slate-200'}`}>
            <input type="radio" name="role" value={r.value} checked={form.role === r.value} onChange={set('role')} className="mt-1" />
            <span><span className="block font-medium">{r.label}</span><span className="text-slate-500">{r.help}</span></span>
          </label>
        ))}
      </fieldset>
      <div className="flex gap-2">
        <SubmitButton loading={isLoading} className="btn-primary">Send invite</SubmitButton>
        <button type="button" className="btn-secondary" onClick={onClose}>Cancel</button>
      </div>
    </form>
  )
}
