/**
 * The BookMarked mark, inlined so it never depends on a separate request
 * (an <img> of /favicon.svg can be broken by caches or proxies). Same drawing as public/favicon.svg.
 */
export default function Logo({ className = 'h-7 w-7' }) {
  return (
    <svg viewBox="0 0 32 32" className={className} aria-hidden="true" focusable="false">
      <rect width="32" height="32" rx="7" fill="#1e3a8a" />
      <path d="M10 6h12a1 1 0 0 1 1 1v19.5a.6.6 0 0 1-.95.5L16 22.6l-6.05 4.4a.6.6 0 0 1-.95-.5V7a1 1 0 0 1 1-1z" fill="#fff" />
      <path d="M13 6h6v9l-3-2.2-3 2.2z" fill="#f59e0b" />
    </svg>
  )
}
