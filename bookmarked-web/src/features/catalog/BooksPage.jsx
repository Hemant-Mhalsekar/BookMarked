import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import BookCard, { BookCardSkeleton } from '../../components/BookCard'
import { Alert } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { FORMAT_LABELS } from '../../utils/format'
import { activeFilterCount, readFilters, SORT_OPTIONS, toApiParams, writeFilters } from '../../utils/searchParams'
import { useCategoriesQuery, useSearchBooksQuery } from './catalogApi'

const PRICE_PRESETS = [
  { label: 'Under ₹300', min: '', max: '300' },
  { label: '₹300 – ₹600', min: '300', max: '600' },
  { label: '₹600 – ₹1,000', min: '600', max: '1000' },
  { label: 'Over ₹1,000', min: '1000', max: '' },
]

export default function BooksPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = readFilters(searchParams)
  const { data, isFetching, error } = useSearchBooksQuery(toApiParams(filters))
  const { data: categories = [] } = useCategoriesQuery()
  const [showFilters, setShowFilters] = useState(false)

  /** Changing any filter goes back to page 1. */
  const update = (patch, { keepPage = false } = {}) => {
    const next = { ...filters, ...patch }
    if (!keepPage) next.page = 1
    setSearchParams(writeFilters(next))
  }

  useEffect(() => {
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }, [filters.page])

  const categoryName = findCategoryName(categories, filters.category)
  const heading = filters.q ? `Results for “${filters.q}”` : categoryName ?? 'All books'
  const count = activeFilterCount(filters)

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">{heading}</h1>
          {data && (
            <p className="text-sm text-slate-500">
              {data.totalElements.toLocaleString('en-IN')} {data.totalElements === 1 ? 'book' : 'books'}
            </p>
          )}
        </div>
        <div className="flex items-center gap-2">
          <button type="button" className="btn-secondary lg:hidden" onClick={() => setShowFilters((s) => !s)}>
            Filters{count > 0 && ` (${count})`}
          </button>
          <label className="flex items-center gap-2 text-sm text-slate-600">
            <span className="hidden sm:inline">Sort by</span>
            <select className="input w-auto py-1.5" value={filters.sort} onChange={(e) => update({ sort: e.target.value })}>
              {SORT_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
          </label>
        </div>
      </div>

      {/* Search field for small screens (the navbar search is hidden there) */}
      <form
        className="mt-4 md:hidden"
        role="search"
        onSubmit={(e) => {
          e.preventDefault()
          update({ q: new FormData(e.currentTarget).get('q') })
        }}
      >
        <input name="q" type="search" defaultValue={filters.q} className="input" placeholder="Search books, authors or ISBN" />
      </form>

      <div className="mt-6 grid grid-cols-1 gap-8 lg:grid-cols-[230px_1fr]">
        <aside className={`${showFilters ? 'block' : 'hidden'} space-y-6 lg:block`} aria-label="Filters">
          <FilterGroup title="Category">
            <RadioOption name="category" label="All categories" checked={!filters.category} onChange={() => update({ category: '' })} />
            {categories.map((c) => (
              <div key={c.id}>
                <RadioOption name="category" label={c.name} checked={filters.category === c.slug} onChange={() => update({ category: c.slug })} />
                {c.children.length > 0 && (
                  <div className="ml-5">
                    {c.children.map((ch) => (
                      <RadioOption key={ch.id} name="category" label={ch.name} checked={filters.category === ch.slug}
                                   onChange={() => update({ category: ch.slug })} />
                    ))}
                  </div>
                )}
              </div>
            ))}
          </FilterGroup>

          <FilterGroup title="Format">
            <RadioOption name="format" label="Any format" checked={!filters.format} onChange={() => update({ format: '' })} />
            {Object.entries(FORMAT_LABELS).map(([value, label]) => (
              <RadioOption key={value} name="format" label={label} checked={filters.format === value} onChange={() => update({ format: value })} />
            ))}
          </FilterGroup>

          <FilterGroup title="Price">
            <RadioOption name="price" label="Any price" checked={!filters.minPrice && !filters.maxPrice}
                         onChange={() => update({ minPrice: '', maxPrice: '' })} />
            {PRICE_PRESETS.map((p) => (
              <RadioOption key={p.label} name="price" label={p.label}
                           checked={filters.minPrice === p.min && filters.maxPrice === p.max}
                           onChange={() => update({ minPrice: p.min, maxPrice: p.max })} />
            ))}
          </FilterGroup>

          <FilterGroup title="Rating">
            <RadioOption name="rating" label="Any rating" checked={!filters.minRating} onChange={() => update({ minRating: '' })} />
            {['4', '3'].map((r) => (
              <RadioOption key={r} name="rating" label={`${r}★ & up`} checked={filters.minRating === r} onChange={() => update({ minRating: r })} />
            ))}
          </FilterGroup>

          <label className="flex items-center gap-2 text-sm text-slate-700">
            <input type="checkbox" className="h-4 w-4 rounded border-slate-300 text-brand-700"
                   checked={filters.inStock} onChange={(e) => update({ inStock: e.target.checked })} />
            Available now
          </label>

          {count > 0 && (
            <button type="button" className="link text-sm"
                    onClick={() => update({ category: '', format: '', minPrice: '', maxPrice: '', minRating: '', inStock: false })}>
              Clear all filters
            </button>
          )}
        </aside>

        <section aria-live="polite" aria-busy={isFetching}>
          {error && <Alert type="error">{parseApiError(error).message}</Alert>}
          {!data && !error && (
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-4">
              {Array.from({ length: 8 }, (_, i) => <BookCardSkeleton key={i} />)}
            </div>
          )}
          {data && data.content.length === 0 && (
            <div className="rounded-xl border border-dashed border-slate-300 bg-white p-10 text-center">
              <p className="font-medium">No books match these filters.</p>
              <p className="mt-1 text-sm text-slate-500">Try a different search or <Link to="/books" className="link">browse everything</Link>.</p>
            </div>
          )}
          {data && data.content.length > 0 && (
            <>
              <div className={`grid grid-cols-2 gap-4 transition-opacity sm:grid-cols-3 xl:grid-cols-4 ${isFetching ? 'opacity-60' : ''}`}>
                {data.content.map((b) => <BookCard key={b.id} book={b} />)}
              </div>
              <Pagination page={filters.page} totalPages={data.totalPages} onChange={(p) => update({ page: p }, { keepPage: true })} />
            </>
          )}
        </section>
      </div>
    </div>
  )
}

function findCategoryName(tree, slug) {
  if (!slug) return null
  for (const c of tree) {
    if (c.slug === slug) return c.name
    const child = c.children.find((ch) => ch.slug === slug)
    if (child) return child.name
  }
  return null
}

function FilterGroup({ title, children }) {
  return (
    <fieldset>
      <legend className="mb-2 text-xs font-semibold uppercase tracking-wider text-slate-500">{title}</legend>
      <div className="space-y-1">{children}</div>
    </fieldset>
  )
}

function RadioOption({ name, label, checked, onChange }) {
  return (
    <label className="flex cursor-pointer items-center gap-2 py-0.5 text-sm text-slate-700 hover:text-slate-900">
      <input type="radio" name={name} checked={checked} onChange={onChange} className="h-4 w-4 border-slate-300 text-brand-700" />
      <span className={checked ? 'font-medium text-slate-900' : ''}>{label}</span>
    </label>
  )
}

export function Pagination({ page, totalPages, onChange }) {
  if (totalPages <= 1) return null
  const pages = []
  for (let p = Math.max(1, page - 2); p <= Math.min(totalPages, page + 2); p++) pages.push(p)
  return (
    <nav className="mt-8 flex items-center justify-center gap-1" aria-label="Pagination">
      <button type="button" className="btn-secondary px-3 py-1.5" disabled={page <= 1} onClick={() => onChange(page - 1)}>
        Previous
      </button>
      {pages.map((p) => (
        <button
          key={p}
          type="button"
          aria-current={p === page ? 'page' : undefined}
          onClick={() => onChange(p)}
          className={`btn px-3 py-1.5 ${p === page ? 'bg-brand-800 text-white' : 'text-slate-700 hover:bg-slate-100'}`}
        >
          {p}
        </button>
      ))}
      <button type="button" className="btn-secondary px-3 py-1.5" disabled={page >= totalPages} onClick={() => onChange(page + 1)}>
        Next
      </button>
    </nav>
  )
}
