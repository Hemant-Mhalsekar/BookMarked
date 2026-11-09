import { Link } from 'react-router-dom'
import BookCard, { BookCardSkeleton } from './BookCard'

/** A titled row of book cards (home page, book page). Renders nothing when empty. */
export default function BookRow({ title, books, isLoading = false, link }) {
  if (!isLoading && (!books || books.length === 0)) return null
  return (
    <section>
      <div className="flex items-baseline justify-between">
        <h2 className="text-xl font-semibold">{title}</h2>
        {link && <Link to={link} className="link text-sm">See all</Link>}
      </div>
      <div className="mt-4 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
        {isLoading
          ? Array.from({ length: 6 }, (_, i) => <BookCardSkeleton key={i} />)
          : books.slice(0, 6).map((b) => <BookCard key={b.id} book={b} />)}
      </div>
    </section>
  )
}
