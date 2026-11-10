import { api } from '../../../app/api'

export const inventoryApi = api.injectEndpoints({
  endpoints: (build) => ({
    inventory: build.query({
      query: ({ q, filter = 'ALL', page = 0 }) => ({ url: '/admin/inventory', params: { q: q || undefined, filter, page } }),
      providesTags: ['Inventory'],
    }),
    adjustStock: build.mutation({
      query: ({ variantId, ...body }) => ({ url: `/admin/inventory/${variantId}/adjust`, method: 'POST', body }),
      invalidatesTags: (_r, _e, { variantId }) => ['Inventory', { type: 'StockLedger', id: variantId }, 'AdminBook', 'Audit'],
    }),
    stockLedger: build.query({
      query: ({ variantId, page = 0 }) => ({ url: `/admin/inventory/${variantId}/movements`, params: { page } }),
      providesTags: (_r, _e, { variantId }) => [{ type: 'StockLedger', id: variantId }],
    }),
  }),
})

export const { useInventoryQuery, useAdjustStockMutation, useStockLedgerQuery } = inventoryApi

export const MOVEMENT_LABELS = {
  RESTOCK: 'Restock',
  ADJUSTMENT: 'Correction',
  SALE: 'Sale',
  RESERVATION: 'Held for payment',
  RELEASE: 'Released',
  CANCEL: 'Cancelled',
  RETURN: 'Returned',
}

const FORMATS = { PAPERBACK: 'Paperback', HARDCOVER: 'Hardcover', EBOOK: 'E-book' }
export const formatLabel = (f) => FORMATS[f] ?? f

/**
 * Client-side check matching the server: a restock adds at least one copy; a
 * correction changes the count and says why; nothing takes stock below zero.
 * `quantity` is the signed change. Returns a map of field errors.
 */
export function validateAdjustment({ reason, quantity, note }, stock) {
  const errors = {}
  const q = Number(quantity)
  if (!Number.isInteger(q) || q === 0) errors.quantity = 'Enter a whole number of copies'
  else if (Math.abs(q) > 10000) errors.quantity = 'At most 10,000 copies at a time'
  else if (reason === 'RESTOCK' && q < 0) errors.quantity = 'A restock adds copies'
  else if (stock + q < 0) errors.quantity = `Only ${stock} in stock`
  if (reason === 'ADJUSTMENT' && (note ?? '').trim().length < 3) errors.note = 'Say why the count is changing'
  return errors
}
