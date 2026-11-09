import { configureStore } from '@reduxjs/toolkit'
import authReducer from '../features/auth/authSlice'
import guestStackReducer, { GUEST_STACK_KEY, loadGuestStack } from '../features/stack/guestStackSlice'
import { saveJson } from '../utils/storage'
import { api } from './api'

export function makeStore(preloadedState) {
  return configureStore({
    reducer: {
      auth: authReducer,
      guestStack: guestStackReducer,
      [api.reducerPath]: api.reducer,
    },
    middleware: (getDefault) => getDefault().concat(api.middleware),
    preloadedState,
  })
}

export const store = makeStore({ guestStack: loadGuestStack() })

// Keep the guest Stack in localStorage so it survives reloads.
let lastItems = store.getState().guestStack.items
store.subscribe(() => {
  const items = store.getState().guestStack.items
  if (items !== lastItems) {
    lastItems = items
    saveJson(GUEST_STACK_KEY, items)
  }
})
