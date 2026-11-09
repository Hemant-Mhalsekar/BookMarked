import { api } from '../../app/api'

// Every Stack endpoint answers with the full, freshly priced Stack, so mutations
// write their response straight into the cached `stack` query (no refetch needed).
const writeStack = async (_arg, { dispatch, queryFulfilled }) => {
  try {
    const { data } = await queryFulfilled
    dispatch(stackApi.util.upsertQueryData('stack', undefined, data))
  } catch {
    // the caller shows the error
  }
}

export const stackApi = api.injectEndpoints({
  endpoints: (build) => ({
    stack: build.query({
      query: () => '/cart',
      providesTags: ['Stack'],
    }),
    previewStack: build.query({
      query: (items) => ({ url: '/cart/preview', method: 'POST', body: { items } }),
    }),
    addToStack: build.mutation({
      query: ({ variantId, quantity = 1 }) => ({ url: '/cart/items', method: 'POST', body: { variantId, quantity } }),
      onQueryStarted: writeStack,
    }),
    updateStackItem: build.mutation({
      query: ({ itemId, quantity }) => ({ url: `/cart/items/${itemId}`, method: 'PATCH', body: { quantity } }),
      onQueryStarted: writeStack,
    }),
    removeStackItem: build.mutation({
      query: (itemId) => ({ url: `/cart/items/${itemId}`, method: 'DELETE' }),
      onQueryStarted: writeStack,
    }),
    saveForLater: build.mutation({
      query: (itemId) => ({ url: `/cart/items/${itemId}/save-for-later`, method: 'POST' }),
      onQueryStarted: writeStack,
      invalidatesTags: ['Bookmark'],
    }),
    acceptPrices: build.mutation({
      query: () => ({ url: '/cart/accept-prices', method: 'POST' }),
      onQueryStarted: writeStack,
    }),
    mergeStack: build.mutation({
      query: (items) => ({ url: '/cart/merge', method: 'POST', body: { items } }),
      onQueryStarted: writeStack,
    }),
  }),
})

export const {
  useStackQuery,
  usePreviewStackQuery,
  useAddToStackMutation,
  useUpdateStackItemMutation,
  useRemoveStackItemMutation,
  useSaveForLaterMutation,
  useAcceptPricesMutation,
  useMergeStackMutation,
} = stackApi
