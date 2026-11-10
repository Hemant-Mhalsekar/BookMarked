import { describe, expect, it } from 'vitest'
import { actionLabel, describeChange, roleLabel } from './peopleApi'

describe('audit text', () => {
  it('labels actions', () => {
    expect(actionLabel('Staff.changeRole')).toBe('Staff · change role')
    expect(actionLabel('Order.ship')).toBe('Order · ship')
  })

  it('describes before/after changes', () => {
    expect(describeChange('{"role":"ORDER_MANAGER"}', '{"role":"INVENTORY_MANAGER"}'))
      .toBe('role: "ORDER_MANAGER" → "INVENTORY_MANAGER"')
  })

  it('describes generic requests, including refusals', () => {
    expect(describeChange(null, '{"method":"POST","path":"/api/admin/orders/BM-1/ship","status":200,"params":{"orderNumber":"BM-1"}}'))
      .toBe('POST /api/admin/orders/BM-1/ship · ok (200) · orderNumber=BM-1')
    expect(describeChange(null, '{"method":"POST","path":"/api/admin/staff/3/block","status":403}'))
      .toBe('POST /api/admin/staff/3/block · refused (403)')
    expect(describeChange(null, '{"name":"Asha","role":"ORDER_MANAGER"}')).toBe('name: Asha · role: ORDER_MANAGER')
  })

  it('names roles', () => {
    expect(roleLabel('ORDER_MANAGER')).toBe('Order Manager')
    expect(roleLabel('SOMETHING')).toBe('SOMETHING')
  })
})
