import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react'
import { sessionEnded, sessionStarted } from '../features/auth/authSlice'

const baseUrl = import.meta.env.VITE_API_URL || '/api'

const rawBaseQuery = fetchBaseQuery({
  baseUrl,
  credentials: 'include', // send the httpOnly refresh cookie to /api/auth/*
  prepareHeaders: (headers, { getState }) => {
    const token = getState().auth.accessToken
    if (token) headers.set('Authorization', `Bearer ${token}`)
    // Required by the backend on cookie-based endpoints (CSRF defence)
    headers.set('X-Requested-With', 'XMLHttpRequest')
    return headers
  },
})

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

let refreshInFlight = null

/**
 * Exchanges the refresh cookie for a new access token.
 * - Only one refresh runs at a time; concurrent callers share it.
 * - REFRESH_ROTATED means another tab refreshed a moment ago; the browser now
 *   holds the newer cookie, so we retry once.
 */
export function refreshSession(api, extraOptions) {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      const call = () => rawBaseQuery({ url: '/auth/refresh', method: 'POST' }, api, extraOptions)
      let result = await call()
      if (result.error?.data?.error === 'REFRESH_ROTATED') {
        await sleep(400)
        result = await call()
      }
      if (result.data) {
        api.dispatch(sessionStarted(result.data))
      } else {
        api.dispatch(sessionEnded())
      }
      return result
    })().finally(() => {
      refreshInFlight = null
    })
  }
  return refreshInFlight
}

/** On a 401 from any non-auth endpoint: refresh once, then replay the request. */
const baseQueryWithReauth = async (args, api, extraOptions) => {
  let result = await rawBaseQuery(args, api, extraOptions)
  const url = typeof args === 'string' ? args : args.url
  if (result.error?.status === 401 && !url.startsWith('/auth/') && api.getState().auth.accessToken) {
    const refreshed = await refreshSession(api, extraOptions)
    if (refreshed.data) {
      result = await rawBaseQuery(args, api, extraOptions)
    }
  }
  return result
}

export const api = createApi({
  reducerPath: 'api',
  baseQuery: baseQueryWithReauth,
  tagTypes: ['Me', 'Sessions', 'LoginHistory', 'Book', 'AdminBook', 'Category', 'Stack', 'Bookmark', 'RecentlyViewed', 'Address', 'Order', 'Notification', 'AdminOrder', 'Refund', 'Return'],
  endpoints: () => ({}),
})
