import { activeFilterCount, PAGE_SIZE, readFilters, toApiParams, writeFilters } from './searchParams'

describe('book search URL state', () => {
  it('reads defaults from an empty URL', () => {
    expect(readFilters(new URLSearchParams(''))).toEqual({
      q: '', author: '', category: '', format: '', minPrice: '', maxPrice: '', minRating: '',
      inStock: false, sort: 'relevance', page: 1,
    })
  })

  it('ignores unknown sorts and bad page numbers', () => {
    const f = readFilters(new URLSearchParams('sort=cheapest&page=-3'))
    expect(f.sort).toBe('relevance')
    expect(f.page).toBe(1)
  })

  it('round-trips through the URL and omits defaults', () => {
    const filters = { ...readFilters(new URLSearchParams('')), q: 'java', category: 'programming', inStock: true, sort: 'price_asc', page: 3 }
    const url = writeFilters(filters).toString()
    expect(url).toBe('q=java&category=programming&inStock=true&sort=price_asc&page=3')
    expect(readFilters(new URLSearchParams(url))).toEqual(filters)
    expect(writeFilters(readFilters(new URLSearchParams(''))).toString()).toBe('')
  })

  it('builds API params with a 0-based page and only valid numbers', () => {
    const f = readFilters(new URLSearchParams('q=%20clean%20&minPrice=300&maxPrice=abc&page=2&sort=rating'))
    expect(toApiParams(f)).toEqual({ q: 'clean', minPrice: 300, page: 1, size: PAGE_SIZE, sort: 'rating' })
  })

  it('counts active filters', () => {
    expect(activeFilterCount(readFilters(new URLSearchParams('q=x&format=EBOOK&minRating=4&inStock=true')))).toBe(3)
  })
})
