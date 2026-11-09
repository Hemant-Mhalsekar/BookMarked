import { Link } from 'react-router-dom'
import { Alert, FullPageSpinner } from '../../components/ui'
import { useResendVerificationMutation } from '../auth/authApi'
import { useMeQuery } from './accountApi'

export default function AccountPage() {
  const { data: me, isLoading } = useMeQuery()
  const [resend, { isLoading: sending, isSuccess: sent }] = useResendVerificationMutation()

  if (isLoading || !me) return <FullPageSpinner />

  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-10">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Hi, {me.fullName.split(' ')[0]}</h1>
        <p className="text-sm text-slate-600">Manage your BookMarked account.</p>
      </div>

      {!me.emailVerified && (
        <Alert type="warning">
          Your email isn&apos;t verified yet.{' '}
          {sent ? (
            'A new link is on its way.'
          ) : (
            <button type="button" className="link" disabled={sending} onClick={() => resend(me.email)}>
              Resend verification email
            </button>
          )}
        </Alert>
      )}

      <section className="card">
        <h2 className="text-lg font-semibold">Profile</h2>
        <dl className="mt-4 grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-slate-500">Name</dt>
            <dd className="font-medium">{me.fullName}</dd>
          </div>
          <div>
            <dt className="text-slate-500">Email</dt>
            <dd className="flex items-center gap-2 font-medium">
              {me.email}
              <span className={`rounded-full px-2 py-0.5 text-xs ${me.emailVerified ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-800'}`}>
                {me.emailVerified ? 'Verified' : 'Not verified'}
              </span>
            </dd>
          </div>
          <div>
            <dt className="text-slate-500">Mobile</dt>
            <dd className="font-medium">+91 {me.phone}</dd>
          </div>
          <div>
            <dt className="text-slate-500">Account type</dt>
            <dd className="font-medium">{me.accountType === 'STAFF' ? `Staff · ${me.role.replaceAll('_', ' ').toLowerCase()}` : 'Customer'}</dd>
          </div>
        </dl>
        <p className="mt-4 text-xs text-slate-500">Editing your profile and addresses arrives with the full dashboard (Step 9).</p>
      </section>

      <section className="card flex items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold">Security</h2>
          <p className="text-sm text-slate-600">Password, logged-in devices and recent sign-ins.</p>
        </div>
        <Link to="/account/security" className="btn-secondary">Manage</Link>
      </section>
    </div>
  )
}
