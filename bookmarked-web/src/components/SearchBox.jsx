import { useEffect, useId, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useSuggestBooksQuery } from '../features/catalog/catalogApi'

function useDebounced(value, ms) {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return debounced
}

/** Navbar search with title suggestions (debounced, keyboard navigable). */
export default function SearchBox({ className = '' }) {
  const [params] = useSearchParams()
  const [text, setText] = useState(params.get('q') ?? '')
  const [open, setOpen] = useState(false)
  const [highlight, setHighlight] = useState(-1)
  const navigate = useNavigate()
  const listId = useId()

  const term = useDebounced(text.trim(), 250)
  const { data: suggestions = [] } = useSuggestBooksQuery(term, { skip: term.length < 2 })
  const visible = open && term.length >= 2 && suggestions.length > 0

  const go = (q) => {
    setOpen(false)
    setHighlight(-1)
    const query = q.trim()
    navigate(query ? `/books?q=${encodeURIComponent(query)}` : '/books')
  }

  const onKeyDown = (e) => {
    if (!visible) return
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setHighlight((h) => (h + 1) % suggestions.length)
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setHighlight((h) => (h <= 0 ? suggestions.length - 1 : h - 1))
    } else if (e.key === 'Escape') {
      setOpen(false)
    }
  }

  return (
    <form
      role="search"
      className={`relative ${className}`}
      onSubmit={(e) => {
        e.preventDefault()
        go(highlight >= 0 ? suggestions[highlight] : text)
      }}
    >
      <input
        type="search"
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          setOpen(true)
          setHighlight(-1)
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onKeyDown={onKeyDown}
        placeholder="Search books, authors or ISBN"
        aria-label="Search books"
        aria-autocomplete="list"
        aria-controls={listId}
        aria-expanded={visible}
        className="w-full rounded-lg border-0 bg-white/95 py-2 pl-9 pr-3 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-amber-400"
      />
      <svg className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-slate-400" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
        <path fillRule="evenodd" d="M9 3.5a5.5 5.5 0 1 0 3.47 9.77l3.63 3.63a.75.75 0 1 0 1.06-1.06l-3.63-3.63A5.5 5.5 0 0 0 9 3.5ZM5 9a4 4 0 1 1 8 0 4 4 0 0 1-8 0Z" clipRule="evenodd" />
      </svg>
      {visible && (
        <ul id={listId} role="listbox" className="absolute left-0 right-0 top-full z-40 mt-1 overflow-hidden rounded-lg border border-slate-200 bg-white py-1 text-sm shadow-lg">
          {suggestions.map((s, i) => (
            <li key={s} role="option" aria-selected={i === highlight}>
              <button
                type="button"
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => go(s)}
                className={`block w-full px-3 py-2 text-left ${i === highlight ? 'bg-brand-50 text-brand-900' : 'text-slate-700 hover:bg-slate-50'}`}
              >
                {s}
              </button>
            </li>
          ))}
        </ul>
      )}
    </form>
  )
}
