import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, PasswordChecklist, PasswordInput, Spinner, SubmitButton } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { formatDateTime, timeAgo } from '../../utils/format'
import { validatePasswordPair } from '../../utils/validation'
import {
  useChangePasswordMutation,
  useLoginHistoryQuery,
  useRevokeOtherSessionsMutation,
  useRevokeSessionMutation,
  useSessionsQuery,
} from './accountApi'

export default function SecurityPage() {
  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-10">
      <div>
        <Link to="/account" className="link text-sm">← My account</Link>
        <h1 className="mt-2 text-2xl font-bold tracking-tight">Security & sessions</h1>
      </div>
      <ActiveSessions />
      <ChangePassword />
      <LoginHistory />
    </div>
  )
}

function ActiveSessions() {
  const { data: sessions = [], isLoading, error } = useSessionsQuery()
  const [revoke, { isLoading: revoking, originalArgs }] = useRevokeSessionMutation()
  const [revokeOthers, { isLoading: revokingAll, data: revokedAll }] = useRevokeOtherSessionsMutation()
  const others = sessions.filter((s) => !s.current).length

  return (
    <section className="card">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">Logged-in devices</h2>
          <p className="text-sm text-slate-600">Sign out anywhere you don&apos;t recognise.</p>
        </div>
        <button type="button" className="btn-danger" disabled={revokingAll || others === 0} onClick={() => revokeOthers()}>
          {revokingAll && <Spinner className="h-4 w-4" />}
          Log out all other devices
        </button>
      </div>
      {revokedAll && <div className="mt-4"><Alert type="success">{revokedAll.message}</Alert></div>}
      {error && <div className="mt-4"><Alert type="error">{parseApiError(error).message}</Alert></div>}
      {isLoading ? (
        <div className="mt-6 flex justify-center text-brand-800"><Spinner /></div>
      ) : (
        <ul className="mt-4 divide-y divide-slate-100">
          {sessions.map((s) => (
            <li key={s.id} className="flex items-center justify-between gap-4 py-3">
              <div className="text-sm">
                <div className="flex items-center gap-2 font-medium">
                  {s.device}
                  {s.current && (
                    <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700">
                      This device
                    </span>
                  )}
                </div>
                <div className="text-xs text-slate-500">
                  {s.ipAddress ?? 'Unknown IP'} · signed in {formatDateTime(s.createdAt)} · active {timeAgo(s.lastUsedAt)}
                </div>
              </div>
              {!s.current && (
                <button
                  type="button"
                  className="btn-secondary px-3 py-1.5 text-xs"
                  disabled={revoking && originalArgs === s.id}
                  onClick={() => revoke(s.id)}
                >
                  Log out
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function ChangePassword() {
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' })
  const [touched, setTouched] = useState(false)
  const [change, { isLoading, isSuccess, error, data, reset }] = useChangePasswordMutation()
  const errors = touched ? validatePasswordPair(form.newPassword, form.confirmPassword) : {}
  const apiError = parseApiError(error)

  const onChange = (e) => {
    if (isSuccess) reset()
    setForm((f) => ({ ...f, [e.target.name]: e.target.value }))
  }

  const onSubmit = async (e) => {
    e.preventDefault()
    setTouched(true)
    if (Object.keys(validatePasswordPair(form.newPassword, form.confirmPassword)).length) return
    try {
      await change({ currentPassword: form.currentPassword, newPassword: form.newPassword }).unwrap()
      setForm({ currentPassword: '', newPassword: '', confirmPassword: '' })
      setTouched(false)
    } catch {
      // shown below
    }
  }

  return (
    <section className="card">
      <h2 className="text-lg font-semibold">Change password</h2>
      <p className="text-sm text-slate-600">Changing your password signs out every other device.</p>
      <form onSubmit={onSubmit} className="mt-4 max-w-md space-y-4" noValidate>
        {isSuccess && <Alert type="success">{data?.message}</Alert>}
        {apiError && <Alert type="error">{apiError.message}</Alert>}
        <PasswordInput label="Current password" name="currentPassword" autoComplete="current-password"
                       value={form.currentPassword} onChange={onChange} />
        <PasswordInput label="New password" name="newPassword" autoComplete="new-password"
                       value={form.newPassword} onChange={onChange}
                       error={errors.password ?? apiError?.fieldErrors?.newPassword} />
        <PasswordChecklist password={form.newPassword} />
        <PasswordInput label="Confirm new password" name="confirmPassword" autoComplete="new-password"
                       value={form.confirmPassword} onChange={onChange} error={errors.confirmPassword} />
        <SubmitButton loading={isLoading} className="btn-primary" disabled={!form.currentPassword}>
          Update password
        </SubmitButton>
      </form>
    </section>
  )
}

function LoginHistory() {
  const { data: history = [], isLoading } = useLoginHistoryQuery(undefined, { refetchOnMountOrArgChange: true })
  return (
    <section className="card">
      <h2 className="text-lg font-semibold">Recent sign-in activity</h2>
      <p className="text-sm text-slate-600">Your last 10 sign-in attempts.</p>
      {isLoading ? (
        <div className="mt-6 flex justify-center text-brand-800"><Spinner /></div>
      ) : (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-xs uppercase text-slate-500">
              <tr>
                <th className="py-2 pr-4 font-medium">When</th>
                <th className="py-2 pr-4 font-medium">Device</th>
                <th className="py-2 pr-4 font-medium">IP address</th>
                <th className="py-2 font-medium">Result</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {history.map((h) => (
                <tr key={h.id}>
                  <td className="whitespace-nowrap py-2 pr-4">{formatDateTime(h.at)}</td>
                  <td className="py-2 pr-4">{h.device}</td>
                  <td className="py-2 pr-4 text-slate-600">{h.ipAddress ?? '—'}</td>
                  <td className="py-2">
                    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${h.success ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700'}`}>
                      {h.success ? 'Success' : 'Failed'}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
