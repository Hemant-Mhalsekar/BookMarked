import { api } from '../../../app/api'

// Every action answers with the refreshed order, which goes straight into the cache.
const writeOrder = (orderNumber) => async (_arg, { dispatch, queryFulfilled }) => {
  try {
    const { data } = await queryFulfilled
    dispatch(adminOrdersApi.util.upsertQueryData('adminOrder', orderNumber, data))
  } catch {
    // the caller shows the error
  }
}

const action = (build, path) =>
  build.mutation({
    query: ({ orderNumber, ...body }) => ({
      url: `/admin/orders/${encodeURIComponent(orderNumber)}/${path}`,
      method: 'POST',
      body: Object.keys(body).length ? body : undefined,
    }),
    onQueryStarted: (arg, api) => writeOrder(arg.orderNumber)(arg, api),
    invalidatesTags: [{ type: 'AdminOrder', id: 'LIST' }, 'Refund'],
  })

export const adminOrdersApi = api.injectEndpoints({
  endpoints: (build) => ({
    adminOrders: build.query({
      query: ({ q, status, itemStatus, page = 0, size = 20 } = {}) => ({
        url: '/admin/orders',
        params: Object.fromEntries(Object.entries({ q, status, itemStatus, page, size }).filter(([, v]) => v !== undefined && v !== '')),
      }),
      providesTags: [{ type: 'AdminOrder', id: 'LIST' }],
    }),
    adminOrder: build.query({
      query: (orderNumber) => `/admin/orders/${encodeURIComponent(orderNumber)}`,
      providesTags: (_r, _e, orderNumber) => [{ type: 'AdminOrder', id: orderNumber }],
    }),
    processItems: action(build, 'process'),
    shipItems: action(build, 'ship'),
    deliverItems: action(build, 'deliver'),
    codCollected: action(build, 'cod-collected'),
    adminCancel: action(build, 'cancel'),
    adminInvoice: build.query({
      query: (orderNumber) => ({
        url: `/admin/orders/${encodeURIComponent(orderNumber)}/invoice`,
        responseHandler: async (res) => (res.ok ? URL.createObjectURL(await res.blob()) : res.json()),
      }),
      keepUnusedDataFor: 0,
    }),

    refunds: build.query({
      query: ({ status, page = 0, size = 20 } = {}) => ({
        url: '/admin/refunds',
        params: Object.fromEntries(Object.entries({ status, page, size }).filter(([, v]) => v !== undefined && v !== '')),
      }),
      providesTags: ['Refund'],
    }),
    retryRefund: build.mutation({
      query: (id) => ({ url: `/admin/refunds/${id}/retry`, method: 'POST' }),
      invalidatesTags: ['Refund', 'AdminOrder'],
    }),
  }),
})

export const {
  useAdminOrdersQuery,
  useAdminOrderQuery,
  useProcessItemsMutation,
  useShipItemsMutation,
  useDeliverItemsMutation,
  useCodCollectedMutation,
  useAdminCancelMutation,
  useLazyAdminInvoiceQuery,
  useRefundsQuery,
  useRetryRefundMutation,
} = adminOrdersApi

/** Which items each action applies to, mirroring FulfilmentService's allowed steps. */
export const NEXT_STEP = {
  PLACED: { action: 'process', label: 'Mark packed' },
  PROCESSING: { action: 'ship', label: 'Ship' },
  SHIPPED: { action: 'deliver', label: 'Mark delivered' },
}

export const ITEM_STATUS_TEXT = {
  PLACED: 'Placed',
  PROCESSING: 'Packing',
  SHIPPED: 'Shipped',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  RETURN_REQUESTED: 'Return requested',
  RETURNED: 'Returned',
}

export const ITEM_STATUS_TONE = {
  PLACED: 'bg-amber-50 text-amber-800',
  PROCESSING: 'bg-sky-50 text-sky-800',
  SHIPPED: 'bg-indigo-50 text-indigo-800',
  DELIVERED: 'bg-emerald-50 text-emerald-700',
  CANCELLED: 'bg-slate-100 text-slate-600',
  RETURN_REQUESTED: 'bg-amber-50 text-amber-800',
  RETURNED: 'bg-slate-100 text-slate-600',
}
