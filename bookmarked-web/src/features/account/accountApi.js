import { api } from '../../app/api'
import { sessionEnded, userUpdated } from '../auth/authSlice'

/** Forget the session in this tab after the server has ended it. */
export const endLocalSession = () => (dispatch) => {
  dispatch(sessionEnded())
  dispatch(api.util.resetApiState())
}

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
    pendingEmail: build.query({
      // 204 when nothing is waiting
      query: () => ({ url: '/account/email-change', responseHandler: (r) => (r.status === 204 ? null : r.json()) }),
      providesTags: ['PendingEmail'],
    }),
    requestEmailChange: build.mutation({
      query: (body) => ({ url: '/account/email-change', method: 'POST', body }),
      invalidatesTags: ['PendingEmail'],
    }),
    cancelEmailChange: build.mutation({
      query: () => ({ url: '/account/email-change', method: 'DELETE' }),
      invalidatesTags: ['PendingEmail'],
    }),
    // the page leaves the protected area first, then ends the local session (endLocalSession)
    deleteAccount: build.mutation({
      query: (body) => ({ url: '/account/delete', method: 'POST', body }),
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
  usePendingEmailQuery,
  useRequestEmailChangeMutation,
  useCancelEmailChangeMutation,
  useDeleteAccountMutation,
} = accountApi
