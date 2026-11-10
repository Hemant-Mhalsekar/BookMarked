import { api } from '../../app/api'

export const libraryApi = api.injectEndpoints({
  endpoints: (build) => ({
    library: build.query({
      query: () => '/library',
      providesTags: ['Library'],
    }),
    libraryIds: build.query({
      query: () => '/library/ids',
      providesTags: ['Library'],
    }),
    libraryBook: build.query({
      query: (variantId) => `/library/${variantId}`,
      providesTags: (_r, _e, id) => [{ type: 'Library', id }],
    }),
    saveProgress: build.mutation({
      query: ({ variantId, page, totalPages }) => ({
        url: `/library/${variantId}/progress`,
        method: 'PUT',
        body: { page, totalPages },
      }),
      // the list re-sorts by last read next time it's opened
      invalidatesTags: ['Library'],
    }),
    readLink: build.mutation({
      query: (variantId) => ({ url: `/library/${variantId}/read-link`, method: 'POST' }),
    }),
    downloadLink: build.mutation({
      query: (variantId) => ({ url: `/library/${variantId}/download-link`, method: 'POST' }),
      invalidatesTags: ['Library'],
    }),

    // staff
    ebookFile: build.query({
      query: (variantId) => `/admin/variants/${variantId}/ebook`,
      providesTags: (_r, _e, id) => [{ type: 'EbookFile', id }],
    }),
    uploadEbook: build.mutation({
      query: ({ variantId, file, sample = false }) => {
        const body = new FormData()
        body.append('file', file)
        // large uploads skip the frontend host's proxy when a direct API address is configured
        return { url: directUrl(`/admin/variants/${variantId}/ebook${sample ? '/sample' : ''}`), method: 'PUT', body }
      },
      invalidatesTags: (_r, _e, { variantId }) => [{ type: 'EbookFile', id: variantId }, 'Book'],
    }),
  }),
})

export const {
  useLibraryQuery,
  useLibraryIdsQuery,
  useLibraryBookQuery,
  useSaveProgressMutation,
  useReadLinkMutation,
  useDownloadLinkMutation,
  useEbookFileQuery,
  useUploadEbookMutation,
} = libraryApi

const API_BASE = import.meta.env.VITE_API_URL || '/api'
/**
 * In production the site proxies /api through its host (so the login cookie stays
 * first-party), but PDFs are big: uploads and file streams go straight to the API
 * when VITE_DIRECT_API_URL is set. Those requests carry a Bearer token or a signed
 * link, never the cookie, so going cross-origin is safe.
 */
const FILES_BASE = import.meta.env.VITE_DIRECT_API_URL || API_BASE

/** An absolute URL on the direct API, or the path unchanged (relative to the normal API base). */
export function directUrl(path) {
  return import.meta.env.VITE_DIRECT_API_URL ? `${FILES_BASE}${path}` : path
}

/** "/api/files/abc" → the URL the browser should fetch (the API may live on another origin). */
export function fileUrl(path) {
  return path.startsWith('/api/') ? `${FILES_BASE}${path.slice(4)}` : path
}

export const sampleUrl = (variantId) => `${FILES_BASE}/books/variants/${variantId}/sample`

/** "Page 45 of 320 · 14%", or null before the first read. */
export function progressText(lastPage, totalPages) {
  if (!lastPage || !totalPages) return null
  return `Page ${lastPage} of ${totalPages} · ${progressPercent(lastPage, totalPages)}%`
}

export function progressPercent(lastPage, totalPages) {
  if (!lastPage || !totalPages) return 0
  return Math.min(100, Math.max(0, Math.round((lastPage / totalPages) * 100)))
}

/** Page numbers typed by the reader, kept inside the book. */
export function clampPage(value, numPages) {
  const n = Math.round(Number(value))
  if (!Number.isFinite(n) || !numPages) return 1
  return Math.min(numPages, Math.max(1, n))
}
