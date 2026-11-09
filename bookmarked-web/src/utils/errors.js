/**
 * Normalises an RTK Query error into { code, message, fieldErrors }.
 * The backend always answers with { status, error, message, fieldErrors }.
 */
export function parseApiError(err) {
  if (!err) return null
  if (err.status === 'FETCH_ERROR') {
    return { code: 'NETWORK', message: 'Cannot reach the server. Check your connection and try again.', fieldErrors: {} }
  }
  const data = err.data ?? {}
  if (err.status === 429 || data.error === 'RATE_LIMITED') {
    return { code: 'RATE_LIMITED', message: 'Too many attempts. Please wait a minute and try again.', fieldErrors: {} }
  }
  return {
    code: data.error ?? 'UNKNOWN',
    message: data.message ?? 'Something went wrong. Please try again.',
    fieldErrors: data.fieldErrors ?? {},
  }
}
