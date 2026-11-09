import { useSelector } from 'react-redux'
import { Link } from 'react-router-dom'
import Can from '../components/Can'
import { useAdminOrdersQuery, useRefundsQuery } from '../features/admin/orders/adminOrdersApi'
import { selectUser } from '../features/auth/authSlice'

function Count({ to, label, count, tone = 'text-slate-900' }) {
  return (
    <Link to={to} className="card block hover:ring-1 hover:ring-brand-700">
      <p className="text-sm text-slate-500">{label}</p>
      <p className={`mt-1 text-3xl font-bold ${tone}`}>{count ?? '—'}</p>
    </Link>
  )
}

function OrderCounts() {
  const toPack = useAdminOrdersQuery({ itemStatus: 'PLACED', size: 1 })
  const packing = useAdminOrdersQuery({ itemStatus: 'PROCESSING', size: 1 })
  const transit = useAdminOrdersQuery({ itemStatus: 'SHIPPED', size: 1 })
  return (
    <>
      <Count to="/admin/orders?view=to-pack" label="Orders to pack" count={toPack.data?.totalElements}
             tone={toPack.data?.totalElements ? 'text-amber-700' : 'text-slate-900'} />
      <Count to="/admin/orders?view=packing" label="Packed, ready to ship" count={packing.data?.totalElements} />
      <Count to="/admin/orders?view=shipped" label="In transit" count={transit.data?.totalElements} />
    </>
  )
}

function FailedRefunds() {
  const { data } = useRefundsQuery({ status: 'FAILED', size: 1 })
  return (
    <Count to="/admin/refunds?status=FAILED" label="Failed refunds" count={data?.totalElements}
           tone={data?.totalElements ? 'text-red-700' : 'text-slate-900'} />
  )
}

export default function AdminHomePage() {
  const user = useSelector(selectUser)
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Admin dashboard</h1>
        <p className="text-sm text-slate-600">
          Signed in as <span className="font-medium">{user.fullName}</span> · {user.role.replaceAll('_', ' ').toLowerCase()}
        </p>
      </div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Can permission="ORDER_READ"><OrderCounts /></Can>
        <Can permission="REFUND_ISSUE"><FailedRefunds /></Can>
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
      <p className="text-sm text-slate-500">Returns, inventory and the rest of the admin panel arrive in later steps.</p>
    </div>
  )
}
