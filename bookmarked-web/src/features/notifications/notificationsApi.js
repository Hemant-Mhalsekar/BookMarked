import { api } from '../../app/api'

export const notificationsApi = api.injectEndpoints({
  endpoints: (build) => ({
    unreadCount: build.query({
      query: () => '/notifications/unread-count',
      providesTags: ['Notification'],
    }),
    notifications: build.query({
      query: ({ unreadOnly = false, page = 0, size = 10 } = {}) => ({
        url: '/notifications',
        params: { unreadOnly, page, size },
      }),
      providesTags: ['Notification'],
    }),
    markRead: build.mutation({
      query: (id) => ({ url: `/notifications/${id}/read`, method: 'POST' }),
      invalidatesTags: ['Notification'],
    }),
    markAllRead: build.mutation({
      query: () => ({ url: '/notifications/read-all', method: 'POST' }),
      invalidatesTags: ['Notification'],
    }),
  }),
})

export const { useUnreadCountQuery, useNotificationsQuery, useMarkReadMutation, useMarkAllReadMutation } =
  notificationsApi

/** Every 30 s while the tab is visible (SSE replaces polling in Phase 3). */
export const NOTIFICATION_POLL_MS = 30_000

/** Dot colour per notification type. */
export const TYPE_TONE = {
  ORDER_PLACED: 'bg-brand-700',
  ORDER_PROCESSING: 'bg-brand-700',
  ORDER_SHIPPED: 'bg-sky-600',
  ORDER_DELIVERED: 'bg-emerald-600',
  ORDER_CANCELLED: 'bg-slate-500',
  PAYMENT_FAILED: 'bg-red-600',
  REFUND_PROCESSED: 'bg-emerald-600',
  REFUND_FAILED: 'bg-red-600',
  RETURN_UPDATE: 'bg-amber-500',
  NEW_ORDER: 'bg-brand-700',
  LOW_STOCK: 'bg-amber-500',
  NEW_RETURN: 'bg-amber-500',
}
