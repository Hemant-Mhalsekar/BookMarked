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
      query: ({ page = 0, size = 10 } = {}) => ({ url: '/orders', params: { page, size } }),
      providesTags: ['Order'],
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
} = checkoutApi
