import { useSelector } from 'react-redux'
import { Link } from 'react-router-dom'
import { BRAND } from '../brand'
import { selectUser } from '../features/auth/authSlice'

const categories = ['Fiction', 'Non-fiction', 'Programming', 'Self-help', 'Science', 'History']

export default function HomePage() {
  const user = useSelector(selectUser)
  return (
    <div>
      <section className="bg-gradient-to-br from-brand-800 to-brand-600 text-white">
        <div className="mx-auto max-w-6xl px-4 py-16 sm:py-20">
          <p className="text-sm font-semibold uppercase tracking-wider text-brand-100">Paperback · Hardcover · E-book</p>
          <h1 className="mt-3 max-w-2xl text-4xl font-bold tracking-tight sm:text-5xl">
            {BRAND.tagline}
          </h1>
          <p className="mt-4 max-w-xl text-brand-100">
            Bookmark the ones you love, build your stack, read e-books right in your browser and get physical
            copies delivered across India.
          </p>
          {!user && (
            <div className="mt-8 flex gap-3">
              <Link to="/signup" className="btn bg-white text-brand-800 hover:bg-brand-50">Create a free account</Link>
              <Link to="/login" className="btn border border-white/40 text-white hover:bg-white/10">Log in</Link>
            </div>
          )}
        </div>
      </section>

      <section className="mx-auto max-w-6xl px-4 py-12">
        <h2 className="text-xl font-semibold">Shop by category</h2>
        <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          {categories.map((c) => (
            <div key={c} className="card p-4 text-center text-sm font-medium text-slate-700">{c}</div>
          ))}
        </div>
        <p className="mt-6 text-sm text-slate-500">
          The catalog, search and book pages arrive in Step 2–3 of the build.
        </p>
      </section>
    </div>
  )
}
