// Pure helpers for the admin book form: API <-> form state, and client-side checks
// that mirror the backend (the backend still validates everything).

export const FORMATS = ['PAPERBACK', 'HARDCOVER', 'EBOOK']

const emptyVariant = (format) => ({
  enabled: false,
  isbn: '',
  pages: '',
  mrp: '',
  sellingPrice: '',
  stock: format === 'EBOOK' ? '' : '0',
})

export function emptyBookForm() {
  return {
    title: '',
    slug: '',
    description: '',
    language: 'English',
    publisher: '',
    publishedYear: '',
    coverImageUrl: '',
    authors: [],
    categoryIds: [],
    featured: false,
    active: true,
    variants: Object.fromEntries(FORMATS.map((f) => [f, emptyVariant(f)])),
  }
}

const str = (v) => (v == null ? '' : String(v))

/** AdminBookDetail from GET /api/admin/books/{id} -> form state. Inactive formats start unticked. */
export function fromAdminBook(book) {
  const form = emptyBookForm()
  Object.assign(form, {
    title: book.title,
    slug: book.slug,
    description: str(book.description),
    language: str(book.language),
    publisher: str(book.publisher),
    publishedYear: str(book.publishedYear),
    coverImageUrl: str(book.coverImageUrl),
    authors: book.authors.map((a) => a.name),
    categoryIds: [...book.categoryIds],
    featured: book.featured,
    active: book.active,
  })
  for (const v of book.variants) {
    form.variants[v.format] = {
      enabled: v.active,
      isbn: v.isbn,
      pages: str(v.pages),
      mrp: str(v.mrp),
      sellingPrice: str(v.sellingPrice),
      stock: v.format === 'EBOOK' ? '' : str(v.stock ?? 0),
    }
  }
  return form
}

const toNumber = (v) => (v === '' || v == null ? null : Number(v))

/** Form state -> BookRequest body. Unticked formats are left out (the API deactivates them). */
export function toBookRequest(form) {
  return {
    title: form.title.trim(),
    slug: form.slug.trim() || null,
    description: form.description.trim() || null,
    language: form.language.trim() || null,
    publisher: form.publisher.trim() || null,
    publishedYear: toNumber(form.publishedYear),
    coverImageUrl: form.coverImageUrl.trim() || null,
    authors: form.authors,
    categoryIds: form.categoryIds,
    featured: form.featured,
    active: form.active,
    variants: FORMATS.filter((f) => form.variants[f].enabled).map((format) => {
      const v = form.variants[format]
      return {
        format,
        isbn: v.isbn.trim(),
        pages: toNumber(v.pages),
        mrp: v.mrp.trim(),
        sellingPrice: v.sellingPrice.trim(),
        stock: format === 'EBOOK' ? null : toNumber(v.stock),
      }
    }),
  }
}

/** ISBN-10 or ISBN-13 with a valid check digit; hyphens and spaces are ignored. */
export function isValidIsbn(raw) {
  const isbn = raw.replace(/[\s-]/g, '').toUpperCase()
  if (/^\d{13}$/.test(isbn)) {
    const sum = [...isbn.slice(0, 12)].reduce((s, d, i) => s + Number(d) * (i % 2 === 0 ? 1 : 3), 0)
    return (10 - (sum % 10)) % 10 === Number(isbn[12])
  }
  if (/^\d{9}[\dX]$/.test(isbn)) {
    const sum = [...isbn].reduce((s, c, i) => s + (c === 'X' ? 10 : Number(c)) * (10 - i), 0)
    return sum % 11 === 0
  }
  return false
}

const LABELS = { PAPERBACK: 'Paperback', HARDCOVER: 'Hardcover', EBOOK: 'E-book' }

/** Returns { field: message }; variant errors use keys like "PAPERBACK.isbn". */
export function validateBookForm(form) {
  const errors = {}
  if (!form.title.trim()) errors.title = 'Title is required'
  if (form.slug && !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(form.slug.trim())) {
    errors.slug = 'Use lowercase letters, digits and single hyphens'
  }
  if (form.publishedYear && !(Number(form.publishedYear) >= 1450 && Number(form.publishedYear) <= 2100)) {
    errors.publishedYear = 'Enter a valid year'
  }
  if (form.coverImageUrl && !/^https:\/\/\S+$/.test(form.coverImageUrl.trim())) {
    errors.coverImageUrl = 'Must be an https:// link'
  }
  if (form.authors.length === 0) errors.authors = 'Add at least one author'
  if (form.categoryIds.length === 0) errors.categoryIds = 'Pick at least one category'

  const enabled = FORMATS.filter((f) => form.variants[f].enabled)
  if (enabled.length === 0) errors.variants = 'Add at least one format'

  const seenIsbns = new Set()
  for (const f of enabled) {
    const v = form.variants[f]
    const key = (k) => `${f}.${k}`
    const isbn = v.isbn.replace(/[\s-]/g, '').toUpperCase()
    if (!isValidIsbn(v.isbn)) errors[key('isbn')] = 'Enter a valid ISBN-10 or ISBN-13'
    else if (seenIsbns.has(isbn)) errors[key('isbn')] = 'Each format needs its own ISBN'
    seenIsbns.add(isbn)

    const mrp = Number(v.mrp)
    const price = Number(v.sellingPrice)
    if (!(v.mrp !== '' && mrp >= 1)) errors[key('mrp')] = 'MRP must be at least ₹1'
    if (!(v.sellingPrice !== '' && price >= 1)) errors[key('sellingPrice')] = 'Price must be at least ₹1'
    else if (mrp >= 1 && price > mrp) errors[key('sellingPrice')] = 'Cannot be higher than MRP'
    if (v.pages !== '' && !(Number.isInteger(Number(v.pages)) && Number(v.pages) >= 1)) errors[key('pages')] = 'Whole number'
    if (f !== 'EBOOK' && !(v.stock !== '' && Number.isInteger(Number(v.stock)) && Number(v.stock) >= 0)) {
      errors[key('stock')] = 'Enter stock (0 or more)'
    }
  }
  return errors
}

export const formatLabel = (f) => LABELS[f]
