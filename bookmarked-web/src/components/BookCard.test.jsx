import { render, screen } from '@testing-library/react'
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router-dom'
import { makeStore } from '../app/store'
import BookCard from './BookCard'

const book = {
  id: 1,
  slug: 'clean-code',
  title: 'Clean Code',
  authors: ['Robert C. Martin'],
  coverImageUrl: null,
  fromPrice: 399,
  mrp: 599,
  discountPercent: 33,
  avgRating: 4.7,
  ratingCount: 1248,
  formats: ['PAPERBACK', 'EBOOK'],
  badges: ['EBOOK_AVAILABLE', 'LOW_STOCK'],
  stockLeft: 2,
}

const renderCard = (b) =>
  render(
    <Provider store={makeStore()}>
      <MemoryRouter><BookCard book={b} /></MemoryRouter>
    </Provider>,
  )

describe('BookCard', () => {
  it('shows price, discount, rating and badges, linking to the book', () => {
    renderCard(book)
    expect(screen.getByRole('link')).toHaveAttribute('href', '/book/clean-code')
    expect(screen.getByText('Clean Code', { selector: 'h3' })).toBeInTheDocument()
    expect(screen.getByText('₹399')).toBeInTheDocument()
    expect(screen.getByText('₹599')).toBeInTheDocument()
    expect(screen.getByText('33% off')).toBeInTheDocument()
    expect(screen.getByLabelText('Rated 4.7 out of 5')).toBeInTheDocument()
    expect(screen.getByText('Only 2 left')).toBeInTheDocument()
    expect(screen.getByText('E-book')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Cover of Clean Code' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Bookmark Clean Code' })).toBeInTheDocument()
  })

  it('marks out-of-stock books and hides the discount when there is none', () => {
    renderCard({ ...book, discountPercent: 0, badges: ['OUT_OF_STOCK'], stockLeft: null, ratingCount: 0 })
    expect(screen.getByText('Out of stock')).toBeInTheDocument()
    expect(screen.queryByText(/% off/)).not.toBeInTheDocument()
    expect(screen.getByText('No ratings yet')).toBeInTheDocument()
  })
})
