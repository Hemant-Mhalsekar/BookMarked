import { api } from '../../app/api'

export const checkoutApi = api.injectEndpoints({
  endpoints: (build) => ({
    // Addresses
    addresses: build.query({
      query: () => '/account/addresses',
      providesTags: ['Address'],
    }),
    createAddress: build.mutation({
      query: (body) => ({ url: '/account/addresses', method: 'POST', body }),
      invalidatesTags: ['Address'],
    }),
    updateAddress: build.mutation({
      query: ({ id, ...body }) => ({ url: `/account/addresses/${id}`, method: 'PUT', body }),
      invalidatesTags: ['Address'],
    }),
    setDefaultAddress: build.mutation({
      query: (id) => ({ url: `/account/addresses/${id}/default`, method: 'PATCH' }),
      invalidatesTags: ['Address'],
    }),
    deleteAddress: build.mutation({
      query: (id) => ({ url: `/account/addresses/${id}`, method: 'DELETE' }),
      invalidatesTags: ['Address'],
    }),

    // Checkout
    createOrder: build.mutation({
      query: ({ idempotencyKey, ...body }) => ({
        url: '/checkout/create-order',
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
      // COD confirms immediately and clears the Stack
      invalidatesTags: (result) => (result?.status === 'CONFIRMED' ? ['Stack', 'Order'] : ['Order']),
    }),
    verifyPayment: build.mutation({
      query: (body) => ({ url: '/checkout/verify', method: 'POST', body }),
      invalidatesTags: ['Stack', 'Order'],
    }),
    retryPayment: build.mutation({
      query: (orderNumber) => ({ url: `/checkout/${encodeURIComponent(orderNumber)}/retry`, method: 'POST' }),
    }),

    // Orders
    orders: build.query({
      query: ({ tab = 'ALL', page = 0, size = 10 } = {}) => ({ url: '/orders', params: { tab, page, size } }),
      providesTags: ['Order'],
    }),
    cancelOrder: build.mutation({
      // itemIds omitted = everything that can still be cancelled
      query: ({ orderNumber, itemIds, reason }) => ({
        url: `/orders/${encodeURIComponent(orderNumber)}/cancel`,
        method: 'POST',
        body: { itemIds: itemIds ?? null, reason: reason || null },
      }),
      invalidatesTags: ['Order', 'Notification'],
    }),
    // Returns a temporary object URL for the PDF (a string, so it can live in the cache)
    invoice: build.query({
      query: (orderNumber) => ({
        url: `/orders/${encodeURIComponent(orderNumber)}/invoice`,
        responseHandler: async (res) => (res.ok ? URL.createObjectURL(await res.blob()) : res.json()),
      }),
      keepUnusedDataFor: 0,
    }),
    order: build.query({
      query: (orderNumber) => `/orders/${encodeURIComponent(orderNumber)}`,
      providesTags: (_r, _e, orderNumber) => [{ type: 'Order', id: orderNumber }, 'Order'],
    }),
  }),
})

export const {
  useAddressesQuery,
  useCreateAddressMutation,
  useUpdateAddressMutation,
  useSetDefaultAddressMutation,
  useDeleteAddressMutation,
  useCreateOrderMutation,
  useVerifyPaymentMutation,
  useRetryPaymentMutation,
  useOrdersQuery,
  useOrderQuery,
  useCancelOrderMutation,
  useLazyInvoiceQuery,
} = checkoutApi
