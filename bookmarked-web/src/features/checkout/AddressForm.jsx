import { useState } from 'react'
import { Alert, SubmitButton, TextInput } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { validateAddress } from '../../utils/validation'
import { useCreateAddressMutation, useUpdateAddressMutation } from './checkoutApi'

const EMPTY = { fullName: '', phone: '', line1: '', line2: '', city: '', state: '', pincode: '', type: 'HOME', makeDefault: false }

/** Add or edit an address. Calls onSaved(address) with the server's copy. */
export default function AddressForm({ address, defaults, onSaved, onCancel, submitLabel = 'Save address' }) {
  const [form, setForm] = useState(() =>
    address
      ? { ...EMPTY, ...address, line2: address.line2 ?? '', type: address.type ?? 'HOME', makeDefault: address.isDefault }
      : { ...EMPTY, ...defaults },
  )
  const [errors, setErrors] = useState({})
  const [serverError, setServerError] = useState(null)
  const [create, createState] = useCreateAddressMutation()
  const [update, updateState] = useUpdateAddressMutation()

  const set = (field) => (e) => {
    const value = e.target.type === 'checkbox' ? e.target.checked : e.target.value
    setForm((f) => ({ ...f, [field]: value }))
    setErrors((er) => ({ ...er, [field]: undefined }))
  }

  const submit = async (e) => {
    e.preventDefault()
    setServerError(null)
    const found = validateAddress(form)
    setErrors(found)
    if (Object.keys(found).length) return
    const body = {
      fullName: form.fullName.trim(),
      phone: form.phone.trim(),
      line1: form.line1.trim(),
      line2: form.line2.trim() || null,
      city: form.city.trim(),
      state: form.state.trim(),
      pincode: form.pincode.trim(),
      type: form.type,
      makeDefault: form.makeDefault,
    }
    try {
      const saved = address ? await update({ id: address.id, ...body }).unwrap() : await create(body).unwrap()
      onSaved?.(saved)
    } catch (err) {
      const parsed = parseApiError(err)
      setErrors(parsed.fieldErrors)
      setServerError(parsed.message)
    }
  }

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      {serverError && <Alert type="error">{serverError}</Alert>}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <TextInput label="Full name" autoComplete="name" value={form.fullName} onChange={set('fullName')} error={errors.fullName} />
        <TextInput label="Mobile number" inputMode="numeric" maxLength={10} autoComplete="tel-national" value={form.phone}
                   onChange={set('phone')} error={errors.phone} />
      </div>
      <TextInput label="Flat, house no., building, street" autoComplete="address-line1" value={form.line1}
                 onChange={set('line1')} error={errors.line1} />
      <TextInput label="Area, landmark (optional)" autoComplete="address-line2" value={form.line2}
                 onChange={set('line2')} error={errors.line2} />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <TextInput label="City" autoComplete="address-level2" value={form.city} onChange={set('city')} error={errors.city} />
        <TextInput label="State" autoComplete="address-level1" value={form.state} onChange={set('state')} error={errors.state} />
        <TextInput label="Pincode" inputMode="numeric" maxLength={6} autoComplete="postal-code" value={form.pincode}
                   onChange={set('pincode')} error={errors.pincode} />
      </div>
      <fieldset className="flex flex-wrap items-center gap-4 text-sm">
        <legend className="sr-only">Address type</legend>
        {['HOME', 'WORK', 'OTHER'].map((t) => (
          <label key={t} className="flex items-center gap-1.5">
            <input type="radio" name="address-type" value={t} checked={form.type === t} onChange={set('type')} />
            {t.charAt(0) + t.slice(1).toLowerCase()}
          </label>
        ))}
        <label className="ml-auto flex items-center gap-1.5">
          <input type="checkbox" checked={Boolean(form.makeDefault)} onChange={set('makeDefault')} />
          Make this my default address
        </label>
      </fieldset>
      <div className="flex gap-3">
        <SubmitButton loading={createState.isLoading || updateState.isLoading} className="btn-primary">
          {submitLabel}
        </SubmitButton>
        {onCancel && (
          <button type="button" className="btn-secondary" onClick={onCancel}>
            Cancel
          </button>
        )}
      </div>
    </form>
  )
}
