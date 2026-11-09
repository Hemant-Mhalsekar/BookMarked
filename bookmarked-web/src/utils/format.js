const dateTime = new Intl.DateTimeFormat('en-IN', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
})

export const formatDateTime = (iso) => (iso ? dateTime.format(new Date(iso)) : '—')

/** "just now", "5 min ago", "3 h ago", or a date. */
export function timeAgo(iso, now = Date.now()) {
  if (!iso) return '—'
  const diff = Math.max(0, now - new Date(iso).getTime())
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} h ago`
  return formatDateTime(iso)
}

const rupees = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
})

/** 699 -> "₹699", 1099.5 -> "₹1,099.5" */
export const formatPrice = (value) => (value == null || value === '' ? '—' : rupees.format(Number(value)))

export const FORMAT_LABELS = { PAPERBACK: 'Paperback', HARDCOVER: 'Hardcover', EBOOK: 'E-book' }
