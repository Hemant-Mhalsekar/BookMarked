import { emptyBookForm, fromAdminBook, isValidIsbn, toBookRequest, validateBookForm } from './bookForm'

const validForm = () => {
  const f = emptyBookForm()
  f.title = 'Clean Code'
  f.authors = ['Robert C. Martin']
  f.categoryIds = [3]
  f.variants.PAPERBACK = { enabled: true, isbn: '978-0-13-235088-4', pages: '464', mrp: '899', sellingPrice: '699', stock: '12' }
  return f
}

describe('isValidIsbn (mirrors the backend checksum)', () => {
  it.each(['978-0-13-235088-4', '9780132350884', '0-306-40615-2', '080442957X'])('accepts %s', (isbn) => {
    expect(isValidIsbn(isbn)).toBe(true)
  })
  it.each(['9780132350885', '978013235088', '0-306-40615-3', 'abc'])('rejects %s', (isbn) => {
    expect(isValidIsbn(isbn)).toBe(false)
  })
})

describe('validateBookForm', () => {
  it('accepts a complete form', () => {
    expect(validateBookForm(validForm())).toEqual({})
  })

  it('requires title, author, category and a format', () => {
    const errors = validateBookForm(emptyBookForm())
    expect(Object.keys(errors).sort()).toEqual(['authors', 'categoryIds', 'title', 'variants'])
  })

  it('checks prices, stock and duplicate ISBNs per format', () => {
    const f = validForm()
    f.variants.PAPERBACK.sellingPrice = '999'
    f.variants.PAPERBACK.stock = ''
    f.variants.EBOOK = { enabled: true, isbn: '9780132350884', pages: '', mrp: '599', sellingPrice: '399', stock: '' }
    expect(validateBookForm(f)).toEqual({
      'PAPERBACK.sellingPrice': 'Cannot be higher than MRP',
      'PAPERBACK.stock': 'Enter stock (0 or more)',
      'EBOOK.isbn': 'Each format needs its own ISBN',
    })
  })
})

describe('form <-> API mapping', () => {
  it('sends only ticked formats, numbers as numbers and no stock for e-books', () => {
    const f = validForm()
    f.variants.EBOOK = { enabled: true, isbn: '0-306-40615-2', pages: '', mrp: '599', sellingPrice: '399', stock: '5' }
    f.variants.HARDCOVER.isbn = 'ignored because unticked'
    const body = toBookRequest(f)
    expect(body.slug).toBeNull()
    expect(body.variants).toEqual([
      { format: 'PAPERBACK', isbn: '978-0-13-235088-4', pages: 464, mrp: '899', sellingPrice: '699', stock: 12 },
      { format: 'EBOOK', isbn: '0-306-40615-2', pages: null, mrp: '599', sellingPrice: '399', stock: null },
    ])
  })

  it('loads an existing book and leaves inactive formats unticked', () => {
    const form = fromAdminBook({
      id: 1, slug: 'clean-code', title: 'Clean Code', description: null, language: 'English', publisher: null,
      publishedYear: 2008, coverImageUrl: null, featured: true, active: true,
      authors: [{ id: 9, name: 'Robert C. Martin' }], categoryIds: [3, 7],
      variants: [
        { format: 'PAPERBACK', isbn: '9780132350884', pages: 464, mrp: 899, sellingPrice: 699, stock: 12, active: true },
        { format: 'EBOOK', isbn: '0306406152', pages: null, mrp: 599, sellingPrice: 399, stock: null, active: false },
      ],
    })
    expect(form.publishedYear).toBe('2008')
    expect(form.authors).toEqual(['Robert C. Martin'])
    expect(form.variants.PAPERBACK).toMatchObject({ enabled: true, stock: '12', mrp: '899' })
    expect(form.variants.EBOOK.enabled).toBe(false)
    expect(form.variants.HARDCOVER.enabled).toBe(false)
  })
})
