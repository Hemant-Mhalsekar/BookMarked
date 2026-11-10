import { api, refreshSession } from '../../app/api'
import { sessionEnded, sessionStarted } from './authSlice'

export const authApi = api.injectEndpoints({
  endpoints: (build) => ({
    register: build.mutation({
      query: (body) => ({ url: '/auth/register', method: 'POST', body }),
    }),
    verifyEmail: build.mutation({
      query: (token) => ({ url: '/auth/verify-email', method: 'POST', body: { token } }),
      invalidatesTags: ['Me'],
    }),
    resendVerification: build.mutation({
      query: (email) => ({ url: '/auth/resend-verification', method: 'POST', body: { email } }),
    }),
    login: build.mutation({
      query: (body) => ({ url: '/auth/login', method: 'POST', body }),
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(sessionStarted(data))
        } catch {
          // error is shown by the form
        }
      },
    }),
    refresh: build.mutation({
      async queryFn(_arg, apiCtx, extraOptions) {
        const result = await refreshSession(apiCtx, extraOptions)
        return result.data ? { data: result.data } : { error: result.error }
      },
    }),
    logout: build.mutation({
      query: () => ({ url: '/auth/logout', method: 'POST' }),
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        try {
          await queryFulfilled
        } finally {
          dispatch(sessionEnded())
          dispatch(api.util.resetApiState())
        }
      },
    }),
    forgotPassword: build.mutation({
      query: (identifier) => ({ url: '/auth/forgot-password', method: 'POST', body: { identifier } }),
    }),
    resetPassword: build.mutation({
      query: (body) => ({ url: '/auth/reset-password', method: 'POST', body }),
    }),
    acceptInvite: build.mutation({
      query: (body) => ({ url: '/auth/accept-invite', method: 'POST', body }),
    }),
  }),
})

export const {
  useRegisterMutation,
  useVerifyEmailMutation,
  useResendVerificationMutation,
  useLoginMutation,
  useRefreshMutation,
  useLogoutMutation,
  useForgotPasswordMutation,
  useResetPasswordMutation,
  useAcceptInviteMutation,
} = authApi
