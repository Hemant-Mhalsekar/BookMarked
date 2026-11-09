import { useSelector } from 'react-redux'
import Can from '../components/Can'
import { selectUser } from '../features/auth/authSlice'

export default function AdminHomePage() {
  const user = useSelector(selectUser)
  return (
    <div className="mx-auto max-w-6xl space-y-6 px-4 py-10">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Admin dashboard</h1>
        <p className="text-sm text-slate-600">
          Signed in as <span className="font-medium">{user.fullName}</span> · {user.role.replaceAll('_', ' ').toLowerCase()}
        </p>
      </div>
      <div className="card">
        <h2 className="text-lg font-semibold">Your permissions</h2>
        <div className="mt-3 flex flex-wrap gap-2">
          {user.permissions.map((p) => (
            <span key={p} className="rounded-full bg-brand-50 px-3 py-1 text-xs font-medium text-brand-800">{p}</span>
          ))}
        </div>
        <Can permission="STAFF_MANAGE">
          <p className="mt-4 text-sm text-slate-600">You can invite staff (coming in Step 10).</p>
        </Can>
      </div>
      <p className="text-sm text-slate-500">Books, inventory, orders and the rest of the admin panel arrive in later steps.</p>
    </div>
  )
}
