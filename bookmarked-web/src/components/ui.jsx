import { useId, useState } from 'react'
import { passwordChecks } from '../utils/validation'

export function Spinner({ className = 'h-5 w-5' }) {
  return (
    <svg className={`animate-spin text-current ${className}`} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 0 1 8-8v4a4 4 0 0 0-4 4H4z" />
    </svg>
  )
}

export function FullPageSpinner() {
  return (
    <div className="flex min-h-[50vh] items-center justify-center text-brand-800" role="status" aria-label="Loading">
      <Spinner className="h-8 w-8" />
    </div>
  )
}

const alertStyles = {
  error: 'border-red-200 bg-red-50 text-red-800',
  success: 'border-emerald-200 bg-emerald-50 text-emerald-800',
  info: 'border-brand-100 bg-brand-50 text-brand-900',
  warning: 'border-amber-200 bg-amber-50 text-amber-900',
}

export function Alert({ type = 'info', children }) {
  if (!children) return null
  return (
    <div role={type === 'error' ? 'alert' : 'status'} className={`rounded-lg border px-4 py-3 text-sm ${alertStyles[type]}`}>
      {children}
    </div>
  )
}

export function Field({ label, error, hint, children, id }) {
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      {children}
      {error ? (
        <p id={`${id}-error`} className="text-xs text-red-600">
          {error}
        </p>
      ) : hint ? (
        <p className="text-xs text-slate-500">{hint}</p>
      ) : null}
    </div>
  )
}

export function TextInput({ label, error, hint, className = '', ...props }) {
  const autoId = useId()
  const id = props.id ?? autoId
  return (
    <Field label={label} error={error} hint={hint} id={id}>
      <input
        id={id}
        className={`input ${error ? 'input-error' : ''} ${className}`}
        aria-invalid={Boolean(error)}
        aria-describedby={error ? `${id}-error` : undefined}
        {...props}
      />
    </Field>
  )
}

export function PasswordInput({ label = 'Password', error, hint, ...props }) {
  const [visible, setVisible] = useState(false)
  const autoId = useId()
  const id = props.id ?? autoId
  return (
    <Field label={label} error={error} hint={hint} id={id}>
      <div className="relative">
        <input
          id={id}
          type={visible ? 'text' : 'password'}
          className={`input pr-16 ${error ? 'input-error' : ''}`}
          aria-invalid={Boolean(error)}
          aria-describedby={error ? `${id}-error` : undefined}
          {...props}
        />
        <button
          type="button"
          onClick={() => setVisible((v) => !v)}
          className="absolute inset-y-0 right-0 px-3 text-xs font-medium text-slate-500 hover:text-slate-700"
          aria-label={visible ? 'Hide password' : 'Show password'}
        >
          {visible ? 'Hide' : 'Show'}
        </button>
      </div>
    </Field>
  )
}

export function PasswordChecklist({ password }) {
  return (
    <ul className="grid grid-cols-1 gap-1 text-xs sm:grid-cols-2" aria-label="Password rules">
      {passwordChecks.map((c) => {
        const ok = c.test(password)
        return (
          <li key={c.id} className={ok ? 'text-emerald-700' : 'text-slate-500'}>
            <span aria-hidden="true">{ok ? '✓' : '○'}</span> {c.label}
          </li>
        )
      })}
    </ul>
  )
}

export function SubmitButton({ loading, children, className = 'btn-primary w-full', ...props }) {
  return (
    <button type="submit" className={className} disabled={loading || props.disabled} {...props}>
      {loading && <Spinner className="h-4 w-4" />}
      {children}
    </button>
  )
}
