import { useState } from 'react'
import { Alert, FullPageSpinner } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import AddressForm from '../checkout/AddressForm'
import { useAddressesQuery, useDeleteAddressMutation, useSetDefaultAddressMutation } from '../checkout/checkoutApi'

const MAX_ADDRESSES = 5

export default function AddressesPage() {
  const { data: addresses, isLoading } = useAddressesQuery()
  const [setDefault] = useSetDefaultAddressMutation()
  const [remove] = useDeleteAddressMutation()
  const [editing, setEditing] = useState(null) // null | 'new' | address
  const [error, setError] = useState(null)

  if (isLoading) return <FullPageSpinner />

  const run = async (action) => {
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(parseApiError(e).message)
    }
  }

  return (
    <div className="mx-auto max-w-3xl px-4 py-8">
      <div className="flex items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight">Saved addresses</h1>
        {!editing && addresses.length < MAX_ADDRESSES && (
          <button type="button" className="btn-primary" onClick={() => setEditing('new')}>Add address</button>
        )}
      </div>
      <p className="text-sm text-slate-500">Up to {MAX_ADDRESSES} addresses.</p>
      {error && <div className="mt-4"><Alert type="error">{error}</Alert></div>}

      {editing && (
        <div className="mt-6 rounded-xl border border-slate-200 bg-white p-5">
          <h2 className="mb-4 font-semibold">{editing === 'new' ? 'New address' : 'Edit address'}</h2>
          <AddressForm address={editing === 'new' ? null : editing} onSaved={() => setEditing(null)} onCancel={() => setEditing(null)} />
        </div>
      )}

      {addresses.length === 0 && !editing && (
        <p className="mt-10 text-center text-slate-600">No saved addresses yet.</p>
      )}

      <ul className="mt-6 space-y-3">
        {addresses.map((a) => (
          <li key={a.id} className="rounded-xl border border-slate-200 bg-white p-4 text-sm">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <p className="font-medium">
                  {a.fullName}
                  <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold uppercase text-slate-600">{a.type}</span>
                  {a.isDefault && <span className="ml-2 text-xs font-medium text-emerald-700">Default</span>}
                </p>
                <p className="text-slate-600">{[a.line1, a.line2, a.city, a.state].filter(Boolean).join(', ')} – {a.pincode}</p>
                <p className="text-slate-500">{a.phone}</p>
              </div>
              <div className="flex gap-3">
                {!a.isDefault && <button type="button" className="link" onClick={() => run(() => setDefault(a.id).unwrap())}>Make default</button>}
                <button type="button" className="link" onClick={() => setEditing(a)}>Edit</button>
                <button type="button" className="font-medium text-red-700 hover:underline" onClick={() => run(() => remove(a.id).unwrap())}>Delete</button>
              </div>
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}
