// The book search state lives in the URL (?q=java&category=programming&sort=price_asc&page=2)
// so results can be bookmarked, shared and survive a reload.

export const SORT_OPTIONS = [
  { value: 'relevance', label: 'Relevance' },
  { value: 'price_asc', label: 'Price: low to high' },
  { value: 'price_desc', label: 'Price: high to low' },
  { value: 'newest', label: 'Newest' },
  { value: 'rating', label: 'Top rated' },
]

export const PAGE_SIZE = 12

const FILTER_KEYS = ['q', 'author', 'category', 'format', 'minPrice', 'maxPrice', 'minRating', 'inStock', 'sort', 'page']

/** URLSearchParams -> filter object with defaults. `page` is 1-based in the URL. */
export function readFilters(searchParams) {
  const get = (k) => searchParams.get(k) ?? ''
  const page = Number.parseInt(get('page'), 10)
  return {
    q: get('q'),
    author: get('author'),
    category: get('category'),
    format: get('format'),
    minPrice: get('minPrice'),
    maxPrice: get('maxPrice'),
    minRating: get('minRating'),
    inStock: get('inStock') === 'true',
    sort: SORT_OPTIONS.some((o) => o.value === get('sort')) ? get('sort') : 'relevance',
    page: Number.isFinite(page) && page > 0 ? page : 1,
  }
}

/** Filter object -> compact URLSearchParams (defaults are omitted). */
export function writeFilters(filters) {
  const params = new URLSearchParams()
  for (const key of FILTER_KEYS) {
    const value = filters[key]
    if (value === '' || value == null || value === false) continue
    if (key === 'sort' && value === 'relevance') continue
    if (key === 'page' && Number(value) <= 1) continue
    params.set(key, String(value))
  }
  return params
}

/** Filter object -> params for GET /api/books (0-based page, numbers validated). */
export function toApiParams(filters) {
  const params = { page: Math.max(0, filters.page - 1), size: PAGE_SIZE }
  if (filters.q.trim()) params.q = filters.q.trim()
  if (filters.author?.trim()) params.author = filters.author.trim()
  if (filters.category) params.category = filters.category
  if (filters.format) params.format = filters.format
  for (const key of ['minPrice', 'maxPrice', 'minRating']) {
    const n = Number(filters[key])
    if (filters[key] !== '' && Number.isFinite(n) && n >= 0) params[key] = n
  }
  if (filters.inStock) params.inStock = true
  if (filters.sort !== 'relevance') params.sort = filters.sort
  return params
}

/** Number of active filters, for the "Filters (3)" button on mobile. */
export function activeFilterCount(filters) {
  return ['category', 'format', 'minPrice', 'maxPrice', 'minRating'].filter((k) => filters[k] !== '').length +
    (filters.inStock ? 1 : 0)
}
