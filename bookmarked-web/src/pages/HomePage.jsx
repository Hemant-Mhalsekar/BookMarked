import { useSelector } from 'react-redux'
import { Link } from 'react-router-dom'
import { BRAND } from '../brand'
import BookCard, { BookCardSkeleton } from '../components/BookCard'
import { selectUser } from '../features/auth/authSlice'
import { useCategoriesQuery, useSearchBooksQuery } from '../features/catalog/catalogApi'

export default function HomePage() {
  const user = useSelector(selectUser)
  const { data: categories = [] } = useCategoriesQuery()
  const featured = useSearchBooksQuery({ featured: true, size: 6 })
  const newest = useSearchBooksQuery({ sort: 'newest', size: 6 })

  return (
    <div>
      <section className="bg-gradient-to-br from-brand-800 to-brand-600 text-white">
        <div className="mx-auto max-w-6xl px-4 py-16 sm:py-20">
          <p className="text-sm font-semibold uppercase tracking-wider text-brand-100">Paperback · Hardcover · E-book</p>
          <h1 className="mt-3 max-w-2xl text-4xl font-bold tracking-tight sm:text-5xl">{BRAND.tagline}</h1>
          <p className="mt-4 max-w-xl text-brand-100">
            Bookmark the ones you love, build your stack, read e-books right in your browser and get physical
            copies delivered across India.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Link to="/books" className="btn bg-amber-400 text-slate-900 hover:bg-amber-300">Browse books</Link>
            {!user && (
              <Link to="/signup" className="btn border border-white/40 text-white hover:bg-white/10">Create a free account</Link>
            )}
          </div>
        </div>
      </section>

      <div className="mx-auto max-w-6xl space-y-12 px-4 py-12">
        {categories.length > 0 && (
          <section>
            <h2 className="text-xl font-semibold">Shop by category</h2>
            <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
              {categories.map((c) => (
                <Link key={c.id} to={`/books?category=${c.slug}`}
                      className="card p-4 text-center text-sm font-medium text-slate-700 transition hover:border-brand-500 hover:text-brand-800">
                  {c.name}
                </Link>
              ))}
            </div>
          </section>
        )}

        <BookRow title="Featured" query={featured} link="/books" />
        <BookRow title="New arrivals" query={newest} link="/books?sort=newest" />
      </div>
    </div>
  )
}

function BookRow({ title, query, link }) {
  const books = query.data?.content ?? []
  if (query.isSuccess && books.length === 0) return null
  return (
    <section>
      <div className="flex items-baseline justify-between">
        <h2 className="text-xl font-semibold">{title}</h2>
        <Link to={link} className="link text-sm">See all</Link>
      </div>
      <div className="mt-4 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
        {query.isLoading
          ? Array.from({ length: 6 }, (_, i) => <BookCardSkeleton key={i} />)
          : books.slice(0, 6).map((b) => <BookCard key={b.id} book={b} />)}
      </div>
    </section>
  )
}
