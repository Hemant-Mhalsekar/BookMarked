import { createSlice } from '@reduxjs/toolkit'

/**
 * The access token lives only in memory (never localStorage), so injected
 * scripts can't steal it from storage. After a reload we get a new one from
 * the httpOnly refresh cookie (see bootstrap in App.jsx).
 *
 * status: 'idle'  – haven't checked for an existing session yet
 *         'ready' – checked; user is either set or null
 */
const initialState = {
  accessToken: null,
  user: null,
  status: 'idle',
}

const authSlice = createSlice({
  name: 'auth',
  initialState,
  reducers: {
    sessionStarted(state, action) {
      state.accessToken = action.payload.accessToken
      state.user = action.payload.user
      state.status = 'ready'
    },
    sessionEnded(state) {
      state.accessToken = null
      state.user = null
      state.status = 'ready'
    },
    userUpdated(state, action) {
      state.user = action.payload
    },
  },
})

export const { sessionStarted, sessionEnded, userUpdated } = authSlice.actions
export default authSlice.reducer

export const selectUser = (state) => state.auth.user
export const selectAuthStatus = (state) => state.auth.status
export const selectIsStaff = (state) => state.auth.user?.accountType === 'STAFF'
export const selectHasPermission = (permission) => (state) =>
  Boolean(state.auth.user?.permissions?.includes(permission))
