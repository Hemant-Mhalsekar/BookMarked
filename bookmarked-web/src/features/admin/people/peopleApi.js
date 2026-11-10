import { api } from '../../../app/api'

const clean = (params) => Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== ''))

export const peopleApi = api.injectEndpoints({
  endpoints: (build) => ({
    staff: build.query({
      query: () => '/admin/staff',
      providesTags: ['Staff'],
    }),
    createStaff: build.mutation({
      query: (body) => ({ url: '/admin/staff', method: 'POST', body }),
      invalidatesTags: ['Staff', 'Audit'],
    }),
    staffAction: build.mutation({
      // action: resend-invite | block | unblock | sign-out
      query: ({ id, action }) => ({ url: `/admin/staff/${id}/${action}`, method: 'POST' }),
      invalidatesTags: ['Staff', 'Audit'],
    }),
    changeStaffRole: build.mutation({
      query: ({ id, role }) => ({ url: `/admin/staff/${id}/role`, method: 'PATCH', body: { role } }),
      invalidatesTags: ['Staff', 'Audit'],
    }),

    customers: build.query({
      query: ({ q, status = 'ALL', page = 0 } = {}) => ({ url: '/admin/customers', params: clean({ q, status, page }) }),
      providesTags: ['Customer'],
    }),
    customer: build.query({
      query: (id) => `/admin/customers/${id}`,
      providesTags: (_r, _e, id) => [{ type: 'Customer', id }],
    }),
    customerAction: build.mutation({
      // action: block | unblock | sign-out
      query: ({ id, action }) => ({ url: `/admin/customers/${id}/${action}`, method: 'POST' }),
      invalidatesTags: (_r, _e, { id }) => ['Customer', { type: 'Customer', id }, 'Audit'],
    }),

    audit: build.query({
      query: ({ action, entityType, entityId, staffId, page = 0 } = {}) => ({
        url: '/admin/audit',
        params: clean({ action, entityType, entityId, staffId, page }),
      }),
      providesTags: ['Audit'],
    }),
  }),
})

export const {
  useStaffQuery,
  useCreateStaffMutation,
  useStaffActionMutation,
  useChangeStaffRoleMutation,
  useCustomersQuery,
  useCustomerQuery,
  useCustomerActionMutation,
  useAuditQuery,
} = peopleApi

export const STAFF_ROLES = [
  { value: 'ORDER_MANAGER', label: 'Order Manager', help: 'Orders, returns, refunds and reviews' },
  { value: 'INVENTORY_MANAGER', label: 'Inventory Manager', help: 'Books, categories and stock' },
  { value: 'SUPER_ADMIN', label: 'Super Admin', help: 'Everything, including staff and the audit log' },
  { value: 'DEMO_ADMIN', label: 'Demo (read-only)', help: 'Can look around but change nothing' },
]

export const roleLabel = (role) => STAFF_ROLES.find((r) => r.value === role)?.label ?? role

/** "Order.ship" → "Order · ship", "Staff.changeRole" → "Staff · change role". */
export function actionLabel(action) {
  const [area, verb = ''] = String(action).split('.')
  return `${area} · ${verb.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase()}`
}

/** The audit detail JSON as short readable text. */
export function describeChange(oldValue, newValue) {
  const parse = (v) => {
    if (!v) return null
    try {
      return JSON.parse(v)
    } catch {
      return v
    }
  }
  const before = parse(oldValue)
  const after = parse(newValue)
  if (before && after && typeof before === 'object' && typeof after === 'object') {
    return Object.keys(after).map((k) => `${k}: ${JSON.stringify(before[k])} → ${JSON.stringify(after[k])}`).join(', ')
  }
  if (after && typeof after === 'object') {
    const { method, path, status, params, ...rest } = after
    const parts = []
    if (method && path) parts.push(`${method} ${path}`)
    if (status) parts.push(status >= 400 ? `refused (${status})` : `ok (${status})`)
    if (params) parts.push(Object.entries(params).map(([k, v]) => `${k}=${v}`).join(' '))
    Object.entries(rest).forEach(([k, v]) => parts.push(`${k}: ${typeof v === 'object' ? JSON.stringify(v) : v}`))
    return parts.join(' · ')
  }
  return after ? String(after) : ''
}
