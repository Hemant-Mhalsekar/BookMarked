import { useEffect } from 'react'
import { useSelector } from 'react-redux'
import { api } from '../../app/api'
import { selectAuthStatus, selectUser } from '../auth/authSlice'
import { loadJson, saveJson } from '../../utils/storage'

export const RECENT_KEY = 'bm_recently_viewed'
const KEEP = 20

/** Guest history: book ids, newest first. */
export const readGuestRecent = () => {
  const ids = loadJson(RECENT_KEY, [])
  return Array.isArray(ids) ? ids.filter(Number.isInteger).slice(0, KEEP) : []
}

export const pushGuestRecent = (bookId, current = readGuestRecent()) =>
  [bookId, ...current.filter((id) => id !== bookId)].slice(0, KEEP)

export const recentApi = api.injectEndpoints({
  endpoints: (build) => ({
    recentlyViewed: build.query({
      query: () => ({ url: '/recently-viewed', params: { limit: 12 } }),
      providesTags: ['RecentlyViewed'],
    }),
    booksByIds: build.query({
      query: (ids) => ({ url: '/books/by-ids', params: { ids: ids.join(',') } }),
    }),
    recordView: build.mutation({
      query: (bookId) => ({ url: `/recently-viewed/${bookId}`, method: 'POST' }),
      invalidatesTags: ['RecentlyViewed'],
    }),
    mergeRecent: build.mutation({
      query: (bookIds) => ({ url: '/recently-viewed/merge', method: 'POST', body: { bookIds } }),
      invalidatesTags: ['RecentlyViewed'],
    }),
  }),
})

export const { useRecentlyViewedQuery, useBooksByIdsQuery, useRecordViewMutation, useMergeRecentMutation } = recentApi

/** Records that the current visitor opened a book (server for users, browser for guests). */
export function useTrackView(bookId) {
  const user = useSelector(selectUser)
  const status = useSelector(selectAuthStatus)
  const [record] = useRecordViewMutation()
  useEffect(() => {
    // wait until we know whether the visitor is logged in
    if (!bookId || status !== 'ready') return
    if (user) record(bookId)
    else saveJson(RECENT_KEY, pushGuestRecent(bookId))
  }, [bookId, user, status, record])
}

/** Recently viewed book cards for the current visitor, optionally excluding one book. */
export function useRecentBooks(excludeId) {
  const user = useSelector(selectUser)
  const guestIds = user ? [] : readGuestRecent()
  const server = useRecentlyViewedQuery(undefined, { skip: !user })
  const guest = useBooksByIdsQuery(guestIds, { skip: Boolean(user) || guestIds.length === 0 })
  const books = (user ? server.data : guest.data) ?? []
  return books.filter((b) => b.id !== excludeId)
}
