import { useState } from 'react'

const PALETTES = [
  ['#1e3a8a', '#3b5bdb'],
  ['#7c2d12', '#c2410c'],
  ['#14532d', '#15803d'],
  ['#581c87', '#7e22ce'],
  ['#0f766e', '#14b8a6'],
  ['#831843', '#db2777'],
  ['#334155', '#64748b'],
  ['#78350f', '#d97706'],
]

function hash(text) {
  let h = 0
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) | 0
  return Math.abs(h)
}

/**
 * The book's cover image, or a generated typographic cover when there is none
 * (or it fails to load). Colours are stable per title.
 */
export default function BookCover({ title, author, src, className = '', compact = false }) {
  const [failed, setFailed] = useState(false)
  if (src && !failed) {
    return (
      <img
        src={src}
        alt={`Cover of ${title}`}
        loading="lazy"
        onError={() => setFailed(true)}
        className={`aspect-[2/3] w-full rounded-md object-cover shadow-sm ${className}`}
      />
    )
  }
  const [from, to] = PALETTES[hash(title ?? '') % PALETTES.length]
  if (compact) {
    // Thumbnails: colour and ribbon only; text would be unreadable at this size
    return (
      <div role="img" aria-label={`Cover of ${title}`}
           className={`relative aspect-[2/3] w-full overflow-hidden rounded ${className}`}
           style={{ background: `linear-gradient(150deg, ${from}, ${to})` }}>
        <span className="absolute right-1 top-0 h-3 w-1.5 bg-amber-400" />
      </div>
    )
  }
  return (
    <div
      role="img"
      aria-label={`Cover of ${title}`}
      className={`relative flex aspect-[2/3] w-full flex-col justify-between overflow-hidden rounded-md p-3 text-white shadow-sm ${className}`}
      style={{ background: `linear-gradient(150deg, ${from}, ${to})` }}
    >
      <span className="absolute right-3 top-0 h-8 w-4 bg-amber-400" style={{ clipPath: 'polygon(0 0,100% 0,100% 100%,50% 75%,0 100%)' }} />
      <p className="line-clamp-4 pr-5 pt-7 font-serif text-sm font-semibold leading-snug sm:text-base">{title}</p>
      {author && <p className="line-clamp-2 text-[11px] uppercase tracking-wider text-white/80">{author}</p>}
    </div>
  )
}
