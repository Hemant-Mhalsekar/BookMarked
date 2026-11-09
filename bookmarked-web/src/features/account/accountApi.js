import { api } from '../../app/api'
import { userUpdated } from '../auth/authSlice'

export const accountApi = api.injectEndpoints({
  endpoints: (build) => ({
    me: build.query({
      query: () => '/account/me',
      providesTags: ['Me'],
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(userUpdated(data))
        } catch {
          // handled by caller
        }
      },
    }),
    changePassword: build.mutation({
      query: (body) => ({ url: '/account/change-password', method: 'POST', body }),
      invalidatesTags: ['Sessions'],
    }),
    sessions: build.query({
      query: () => '/account/sessions',
      providesTags: ['Sessions'],
    }),
    revokeSession: build.mutation({
      query: (id) => ({ url: `/account/sessions/${id}`, method: 'DELETE' }),
      invalidatesTags: ['Sessions'],
    }),
    revokeOtherSessions: build.mutation({
      query: () => ({ url: '/account/sessions', method: 'DELETE' }),
      invalidatesTags: ['Sessions'],
    }),
    overview: build.query({
      query: () => '/account/overview',
      providesTags: ['Overview', 'Order', 'Library', 'Review'],
    }),
    updateName: build.mutation({
      query: (fullName) => ({ url: '/account/profile', method: 'PATCH', body: { fullName } }),
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(userUpdated(data))
          dispatch(accountApi.util.upsertQueryData('me', undefined, data))
        } catch {
          // the form shows the error
        }
      },
      invalidatesTags: ['Overview'],
    }),
    changePhone: build.mutation({
      query: (body) => ({ url: '/account/phone', method: 'POST', body }),
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(userUpdated(data))
          dispatch(accountApi.util.upsertQueryData('me', undefined, data))
        } catch {
          // the form shows the error
        }
      },
    }),
    loginHistory: build.query({
      query: () => '/account/login-history',
      providesTags: ['LoginHistory'],
    }),
  }),
})

export const {
  useMeQuery,
  useChangePasswordMutation,
  useSessionsQuery,
  useRevokeSessionMutation,
  useRevokeOtherSessionsMutation,
  useLoginHistoryQuery,
  useOverviewQuery,
  useUpdateNameMutation,
  useChangePhoneMutation,
} = accountApi
