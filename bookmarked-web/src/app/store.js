import { configureStore } from '@reduxjs/toolkit'
import authReducer from '../features/auth/authSlice'
import { api } from './api'

export function makeStore(preloadedState) {
  return configureStore({
    reducer: {
      auth: authReducer,
      [api.reducerPath]: api.reducer,
    },
    middleware: (getDefault) => getDefault().concat(api.middleware),
    preloadedState,
  })
}

export const store = makeStore()
