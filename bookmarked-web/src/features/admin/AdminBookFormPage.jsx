import { useMemo, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import BookCover from '../../components/BookCover'
import { Alert, FullPageSpinner, SubmitButton, TextInput } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import {
  useAdminAuthorsQuery,
  useAdminBookQuery,
  useAdminCategoriesQuery,
  useCreateBookMutation,
  useUpdateBookMutation,
} from './adminCatalogApi'
import EbookFilesPanel from './EbookFilesPanel'
import { emptyBookForm, formatLabel, FORMATS, fromAdminBook, toBookRequest, validateBookForm } from './bookForm'

export default function AdminBookFormPage() {
  const { id } = useParams()
  const isEdit = Boolean(id)
  const { data: existing, isLoading, error: loadError } = useAdminBookQuery(id, { skip: !isEdit })

  if (isEdit && isLoading) return <FullPageSpinner />
  if (isEdit && loadError) return <Alert type="error">{parseApiError(loadError).message}</Alert>
  return <BookForm key={existing?.id ?? 'new'} existing={existing} />
}

function BookForm({ existing }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [form, setForm] = useState(() => (existing ? fromAdminBook(existing) : emptyBookForm()))
  const [touched, setTouched] = useState(false)
  const [saved, setSaved] = useState(Boolean(location.state?.created))
  const [createBook, createState] = useCreateBookMutation()
  const [updateBook, updateState] = useUpdateBookMutation()
  const saving = createState.isLoading || updateState.isLoading
  const apiError = parseApiError(createState.error ?? updateState.error)

  const ebookVariant = existing?.variants.find((v) => v.format === 'EBOOK' && v.id)
  const errors = touched ? validateBookForm(form) : {}
  const set = (patch) => {
    setSaved(false)
    setForm((f) => ({ ...f, ...patch }))
  }
  const setVariant = (format, patch) => {
    setSaved(false)
    setForm((f) => ({ ...f, variants: { ...f.variants, [format]: { ...f.variants[format], ...patch } } }))
  }

  const onSubmit = async (e) => {
    e.preventDefault()
    setTouched(true)
    if (Object.keys(validateBookForm(form)).length > 0) return
    const body = toBookRequest(form)
    try {
      if (existing) {
        const result = await updateBook({ id: existing.id, ...body }).unwrap()
        setForm(fromAdminBook(result))
        setSaved(true)
      } else {
        const result = await createBook(body).unwrap()
        navigate(`/admin/books/${result.id}/edit`, { replace: true, state: { created: true } })
      }
    } catch {
      // shown in the banner
    }
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Link to="/admin/books" className="link text-sm">← Books</Link>
          <h1 className="mt-1 text-2xl font-bold tracking-tight">{existing ? `Edit “${existing.title}”` : 'Add a book'}</h1>
        </div>
        <div className="flex gap-2">
          {existing?.active && (
            <Link to={`/book/${existing.slug}`} className="btn-secondary" target="_blank" rel="noreferrer">View in store</Link>
          )}
          <SubmitButton loading={saving} className="btn-primary">{existing ? 'Save changes' : 'Create book'}</SubmitButton>
        </div>
      </div>

      {saved && <Alert type="success">{location.state?.created && !touched ? 'Book created.' : 'Saved.'}</Alert>}
      {apiError && (
        <Alert type="error">
          {apiError.message}
          {Object.keys(apiError.fieldErrors).length > 0 && (
            <ul className="mt-1 list-disc pl-5">
              {Object.entries(apiError.fieldErrors).map(([k, v]) => <li key={k}>{k}: {v}</li>)}
            </ul>
          )}
        </Alert>
      )}
      {touched && Object.keys(errors).length > 0 && <Alert type="error">Please fix the highlighted fields.</Alert>}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-[1fr_220px]">
        <div className="space-y-6">
          <section className="card space-y-4">
            <h2 className="font-semibold">Details</h2>
            <TextInput label="Title" value={form.title} onChange={(e) => set({ title: e.target.value })} error={errors.title} required />
            <TextInput label="URL slug" value={form.slug} onChange={(e) => set({ slug: e.target.value.toLowerCase() })} error={errors.slug}
                       hint={existing ? 'Changing it breaks old links to this book.' : 'Leave empty to generate it from the title.'} />
            <div className="space-y-1.5">
              <label htmlFor="description" className="block text-sm font-medium text-slate-700">Description</label>
              <textarea id="description" rows={5} className="input" value={form.description}
                        onChange={(e) => set({ description: e.target.value })} maxLength={10000} />
            </div>
            <AuthorsInput authors={form.authors} onChange={(authors) => set({ authors })} error={errors.authors} />
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <TextInput label="Publisher" value={form.publisher} onChange={(e) => set({ publisher: e.target.value })} />
              <TextInput label="Year" inputMode="numeric" value={form.publishedYear} error={errors.publishedYear}
                         onChange={(e) => set({ publishedYear: e.target.value.replace(/\D/g, '').slice(0, 4) })} />
              <TextInput label="Language" value={form.language} onChange={(e) => set({ language: e.target.value })} />
            </div>
            <TextInput label="Cover image URL" value={form.coverImageUrl} error={errors.coverImageUrl} placeholder="https://…"
                       hint="Image uploads arrive with the media step; paste an https link for now."
                       onChange={(e) => set({ coverImageUrl: e.target.value })} />
          </section>

          <section className="card space-y-4">
            <div>
              <h2 className="font-semibold">Formats & pricing</h2>
              <p className="text-sm text-slate-500">Tick each format you sell. Each needs its own ISBN.</p>
              {errors.variants && <p className="mt-1 text-xs text-red-600">{errors.variants}</p>}
            </div>
            {FORMATS.map((f) => (
              <VariantRow key={f} format={f} value={form.variants[f]} errors={errors}
                          onChange={(patch) => setVariant(f, patch)}
                          existed={existing?.variants.some((v) => v.format === f)} />
            ))}
          </section>
          {ebookVariant ? (
            <EbookFilesPanel variantId={ebookVariant.id} canWrite />
          ) : form.variants.EBOOK?.enabled ? (
            <p className="text-sm text-slate-500">Save the book first, then upload the e-book PDF here.</p>
          ) : null}
        </div>

        <div className="space-y-6">
          <section className="card space-y-3">
            <h2 className="font-semibold">Visibility</h2>
            <Toggle label="Visible in store" checked={form.active} onChange={(active) => set({ active })} />
            <Toggle label="Featured on home page" checked={form.featured} onChange={(featured) => set({ featured })} />
          </section>
          <CategoryPicker selected={form.categoryIds} onChange={(categoryIds) => set({ categoryIds })} error={errors.categoryIds} />
          <section className="card">
            <h2 className="mb-3 font-semibold">Preview</h2>
            <BookCover title={form.title || 'Untitled'} author={form.authors.join(', ')} src={form.coverImageUrl || null} />
          </section>
        </div>
      </div>
    </form>
  )
}

function VariantRow({ format, value, onChange, errors, existed }) {
  const err = (k) => errors[`${format}.${k}`]
  return (
    <div className={`rounded-lg border p-4 ${value.enabled ? 'border-brand-200 bg-brand-50/40' : 'border-slate-200'}`}>
      <label className="flex items-center gap-2 font-medium">
        <input type="checkbox" className="h-4 w-4 rounded border-slate-300" checked={value.enabled}
               onChange={(e) => onChange({ enabled: e.target.checked })} />
        {formatLabel(format)}
        {existed && !value.enabled && <span className="text-xs font-normal text-slate-500">(hidden from customers; saved orders keep it)</span>}
      </label>
      {value.enabled && (
        <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-5">
          <div className="col-span-2">
            <TextInput label="ISBN" value={value.isbn} error={err('isbn')} onChange={(e) => onChange({ isbn: e.target.value })} />
          </div>
          <TextInput label="MRP (₹)" inputMode="decimal" value={value.mrp} error={err('mrp')}
                     onChange={(e) => onChange({ mrp: e.target.value.replace(/[^\d.]/g, '') })} />
          <TextInput label="Price (₹)" inputMode="decimal" value={value.sellingPrice} error={err('sellingPrice')}
                     onChange={(e) => onChange({ sellingPrice: e.target.value.replace(/[^\d.]/g, '') })} />
          {format === 'EBOOK' ? (
            <div className="flex items-end pb-2 text-xs text-slate-500">No stock for e-books</div>
          ) : (
            <TextInput label="Stock" inputMode="numeric" value={value.stock} error={err('stock')}
                       onChange={(e) => onChange({ stock: e.target.value.replace(/\D/g, '') })} />
          )}
          <TextInput label="Pages" inputMode="numeric" value={value.pages} error={err('pages')}
                     onChange={(e) => onChange({ pages: e.target.value.replace(/\D/g, '') })} />
        </div>
      )}
    </div>
  )
}

function AuthorsInput({ authors, onChange, error }) {
  const [text, setText] = useState('')
  const term = text.trim()
  const { data: suggestions = [] } = useAdminAuthorsQuery(term, { skip: term.length < 2 })

  const add = (name) => {
    const clean = name.trim().replace(/\s+/g, ' ')
    if (clean && !authors.some((a) => a.toLowerCase() === clean.toLowerCase())) onChange([...authors, clean])
    setText('')
  }

  return (
    <div className="space-y-1.5">
      <label htmlFor="author-input" className="block text-sm font-medium text-slate-700">Authors</label>
      <div className={`flex flex-wrap items-center gap-2 rounded-lg border bg-white px-2 py-1.5 ${error ? 'border-red-400' : 'border-slate-300'}`}>
        {authors.map((a) => (
          <span key={a} className="flex items-center gap-1 rounded-full bg-brand-50 px-2.5 py-1 text-xs font-medium text-brand-800">
            {a}
            <button type="button" aria-label={`Remove ${a}`} className="text-brand-700 hover:text-red-700"
                    onClick={() => onChange(authors.filter((x) => x !== a))}>×</button>
          </span>
        ))}
        <input
          id="author-input"
          list="author-suggestions"
          className="min-w-[160px] flex-1 border-0 py-1 text-sm focus:outline-none focus:ring-0"
          placeholder={authors.length ? 'Add another…' : 'Type a name and press Enter'}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ',') {
              e.preventDefault()
              add(text)
            } else if (e.key === 'Backspace' && !text && authors.length) {
              onChange(authors.slice(0, -1))
            }
          }}
          onBlur={() => text.trim() && add(text)}
        />
        <datalist id="author-suggestions">
          {suggestions.map((a) => <option key={a.id} value={a.name} />)}
        </datalist>
      </div>
      {error && <p className="text-xs text-red-600">{error}</p>}
    </div>
  )
}

function CategoryPicker({ selected, onChange, error }) {
  const { data: categories = [] } = useAdminCategoriesQuery()
  const tree = useMemo(() => {
    const top = categories.filter((c) => c.parentId == null)
    return top.map((t) => ({ ...t, children: categories.filter((c) => c.parentId === t.id) }))
  }, [categories])

  const toggle = (id) => onChange(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id])

  const box = (c) => (
    <label key={c.id} className={`flex items-center gap-2 py-0.5 text-sm ${c.active ? 'text-slate-700' : 'text-slate-400'}`}>
      <input type="checkbox" className="h-4 w-4 rounded border-slate-300" checked={selected.includes(c.id)}
             disabled={!c.active && !selected.includes(c.id)} onChange={() => toggle(c.id)} />
      {c.name}{!c.active && ' (inactive)'}
    </label>
  )

  return (
    <section className="card">
      <h2 className="mb-2 font-semibold">Categories</h2>
      {error && <p className="mb-2 text-xs text-red-600">{error}</p>}
      <div className="max-h-80 space-y-1 overflow-y-auto">
        {tree.map((t) => (
          <div key={t.id}>
            {box(t)}
            <div className="ml-5">{t.children.map(box)}</div>
          </div>
        ))}
      </div>
    </section>
  )
}

function Toggle({ label, checked, onChange }) {
  return (
    <label className="flex items-center justify-between gap-3 text-sm">
      {label}
      <button type="button" role="switch" aria-checked={checked} onClick={() => onChange(!checked)}
              className={`relative h-6 w-11 rounded-full transition ${checked ? 'bg-brand-700' : 'bg-slate-300'}`}>
        <span className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition ${checked ? 'left-[22px]' : 'left-0.5'}`} />
      </button>
    </label>
  )
}
