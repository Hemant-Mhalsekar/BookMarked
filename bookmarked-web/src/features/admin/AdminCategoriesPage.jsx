import { useMemo, useState } from 'react'
import Can from '../../components/Can'
import { Alert, Spinner, SubmitButton, TextInput } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import {
  useAdminCategoriesQuery,
  useCreateCategoryMutation,
  useSetCategoryActiveMutation,
  useUpdateCategoryMutation,
} from './adminCatalogApi'

export default function AdminCategoriesPage() {
  const { data: categories = [], isLoading, error } = useAdminCategoriesQuery()
  const [setActive, activeState] = useSetCategoryActiveMutation()
  const [editing, setEditing] = useState(null)

  const tree = useMemo(() => {
    const top = categories.filter((c) => c.parentId == null)
    return top.map((t) => ({ ...t, children: categories.filter((c) => c.parentId === t.id) }))
  }, [categories])
  const topLevel = categories.filter((c) => c.parentId == null)

  const row = (c, child = false) => (
    <li key={c.id} className={`flex items-center justify-between gap-3 py-2.5 ${child ? 'pl-8' : ''}`}>
      {editing === c.id ? (
        <CategoryForm category={c} parents={topLevel} onDone={() => setEditing(null)} />
      ) : (
        <>
          <div className={c.active ? '' : 'text-slate-400'}>
            <span className={child ? 'text-sm' : 'font-medium'}>{c.name}</span>
            <span className="ml-2 text-xs text-slate-400">/{c.slug}</span>
            {!c.active && <span className="ml-2 rounded-full bg-slate-100 px-2 py-0.5 text-xs">Inactive</span>}
          </div>
          <Can permission="CATEGORY_WRITE">
            <div className="flex gap-3 text-xs">
              <button type="button" className="link" onClick={() => setEditing(c.id)}>Edit</button>
              <button type="button" className={c.active ? 'font-medium text-red-700 hover:underline' : 'link'}
                      onClick={() => setActive({ id: c.id, active: !c.active })}>
                {c.active ? 'Deactivate' : 'Activate'}
              </button>
            </div>
          </Can>
        </>
      )}
    </li>
  )

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Categories</h1>
        <p className="text-sm text-slate-500">
          Two levels: top-level categories and subcategories. Categories are never deleted; deactivating hides them from the store.
        </p>
      </div>

      <Can permission="CATEGORY_WRITE">
        <section className="card">
          <h2 className="mb-3 font-semibold">Add a category</h2>
          <CategoryForm parents={topLevel} />
        </section>
      </Can>

      {error && <Alert type="error">{parseApiError(error).message}</Alert>}
      {activeState.error && <Alert type="error">{parseApiError(activeState.error).message}</Alert>}

      <section className="card">
        {isLoading ? (
          <div className="flex justify-center text-brand-800"><Spinner /></div>
        ) : (
          <ul className="divide-y divide-slate-100">
            {tree.map((t) => [row(t), ...t.children.map((c) => row(c, true))])}
          </ul>
        )}
      </section>
    </div>
  )
}

function CategoryForm({ category, parents, onDone }) {
  const [name, setName] = useState(category?.name ?? '')
  const [slug, setSlug] = useState(category?.slug ?? '')
  const [parentId, setParentId] = useState(category?.parentId ?? '')
  const [create, createState] = useCreateCategoryMutation()
  const [update, updateState] = useUpdateCategoryMutation()
  const state = category ? updateState : createState
  const apiError = parseApiError(state.error)

  const onSubmit = async (e) => {
    e.preventDefault()
    const body = { name: name.trim(), slug: slug.trim() || null, parentId: parentId === '' ? null : Number(parentId) }
    try {
      if (category) {
        await update({ id: category.id, ...body }).unwrap()
        onDone?.()
      } else {
        await create(body).unwrap()
        setName('')
        setSlug('')
        setParentId('')
      }
    } catch {
      // shown below
    }
  }

  return (
    <form onSubmit={onSubmit} className="w-full space-y-2">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-[1fr_1fr_1fr_auto] sm:items-end">
        <TextInput label="Name" value={name} onChange={(e) => setName(e.target.value)} required />
        <TextInput label="Slug" value={slug} placeholder="auto from name" onChange={(e) => setSlug(e.target.value.toLowerCase())} />
        <div className="space-y-1.5">
          <label className="block text-sm font-medium text-slate-700" htmlFor={`parent-${category?.id ?? 'new'}`}>Parent</label>
          <select id={`parent-${category?.id ?? 'new'}`} className="input" value={parentId} onChange={(e) => setParentId(e.target.value)}>
            <option value="">— Top level —</option>
            {parents.filter((p) => p.id !== category?.id).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div className="flex gap-2">
          <SubmitButton loading={state.isLoading} className="btn-primary" disabled={!name.trim()}>
            {category ? 'Save' : 'Add'}
          </SubmitButton>
          {category && <button type="button" className="btn-secondary" onClick={onDone}>Cancel</button>}
        </div>
      </div>
      {apiError && <Alert type="error">{apiError.message}</Alert>}
    </form>
  )
}
