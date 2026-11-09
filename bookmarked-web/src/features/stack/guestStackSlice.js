import { createSlice } from '@reduxjs/toolkit'
import { loadJson } from '../../utils/storage'

export const GUEST_STACK_KEY = 'bm_guest_stack'
export const MAX_PER_ITEM = 10

/**
 * Your Stack for visitors who aren't logged in: just variant ids and quantities,
 * saved in localStorage. Prices always come from the server (POST /cart/preview),
 * and the whole thing is merged into the account at login.
 */
function sanitize(items) {
  if (!Array.isArray(items)) return []
  return items
    .filter((i) => Number.isInteger(i?.variantId) && Number.isInteger(i?.quantity) && i.quantity > 0)
    .map((i) => ({ variantId: i.variantId, quantity: Math.min(i.quantity, MAX_PER_ITEM) }))
    .slice(0, 50)
}

export const loadGuestStack = () => ({ items: sanitize(loadJson(GUEST_STACK_KEY, [])) })

const guestStackSlice = createSlice({
  name: 'guestStack',
  initialState: { items: [] },
  reducers: {
    guestAdd(state, action) {
      const { variantId, quantity = 1, single = false } = action.payload
      const existing = state.items.find((i) => i.variantId === variantId)
      const limit = single ? 1 : MAX_PER_ITEM
      if (existing) existing.quantity = Math.min(existing.quantity + quantity, limit)
      else state.items.push({ variantId, quantity: Math.min(quantity, limit) })
    },
    guestSetQuantity(state, action) {
      const { variantId, quantity } = action.payload
      const existing = state.items.find((i) => i.variantId === variantId)
      if (existing) existing.quantity = Math.max(1, Math.min(quantity, MAX_PER_ITEM))
    },
    guestRemove(state, action) {
      state.items = state.items.filter((i) => i.variantId !== action.payload)
    },
    guestClear(state) {
      state.items = []
    },
  },
})

export const { guestAdd, guestSetQuantity, guestRemove, guestClear } = guestStackSlice.actions
export default guestStackSlice.reducer

export const selectGuestItems = (state) => state.guestStack.items
export const selectGuestCount = (state) => state.guestStack.items.reduce((n, i) => n + i.quantity, 0)
