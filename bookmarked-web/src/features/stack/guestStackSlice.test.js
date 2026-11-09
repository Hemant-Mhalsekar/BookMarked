import { saveJson } from '../../utils/storage'
import { pushGuestRecent, readGuestRecent, RECENT_KEY } from '../catalog/recentlyViewed'
import reducer, {
  GUEST_STACK_KEY,
  guestAdd,
  guestClear,
  guestRemove,
  guestSetQuantity,
  loadGuestStack,
  selectGuestCount,
} from './guestStackSlice'

const run = (...actions) => actions.reduce((s, a) => reducer(s, a), { items: [] })

describe('guest Stack', () => {
  beforeEach(() => window.localStorage.clear())

  it('adds and merges quantities for the same format', () => {
    const state = run(guestAdd({ variantId: 1, quantity: 2 }), guestAdd({ variantId: 1, quantity: 3 }), guestAdd({ variantId: 2 }))
    expect(state.items).toEqual([{ variantId: 1, quantity: 5 }, { variantId: 2, quantity: 1 }])
    expect(selectGuestCount({ guestStack: state })).toBe(6)
  })

  it('keeps e-books at one copy and physical books at ten', () => {
    const state = run(
      guestAdd({ variantId: 9, quantity: 1, single: true }),
      guestAdd({ variantId: 9, quantity: 1, single: true }),
      guestAdd({ variantId: 3, quantity: 8 }),
      guestAdd({ variantId: 3, quantity: 8 }),
    )
    expect(state.items).toEqual([{ variantId: 9, quantity: 1 }, { variantId: 3, quantity: 10 }])
  })

  it('updates, removes and clears', () => {
    let state = run(guestAdd({ variantId: 1, quantity: 2 }), guestAdd({ variantId: 2 }))
    state = reducer(state, guestSetQuantity({ variantId: 1, quantity: 0 }))
    expect(state.items[0].quantity).toBe(1)
    state = reducer(state, guestRemove(1))
    expect(state.items).toEqual([{ variantId: 2, quantity: 1 }])
    expect(reducer(state, guestClear()).items).toEqual([])
  })

  it('loads only well-formed items from storage', () => {
    saveJson(GUEST_STACK_KEY, [{ variantId: 1, quantity: 2 }, { variantId: 'x', quantity: 1 }, { variantId: 3, quantity: 99 }, null])
    expect(loadGuestStack().items).toEqual([{ variantId: 1, quantity: 2 }, { variantId: 3, quantity: 10 }])
    window.localStorage.setItem(GUEST_STACK_KEY, '{not json')
    expect(loadGuestStack().items).toEqual([])
  })
})

describe('guest recently viewed', () => {
  beforeEach(() => window.localStorage.clear())

  it('keeps newest first without duplicates', () => {
    expect(pushGuestRecent(3, [1, 2, 3])).toEqual([3, 1, 2])
    expect(pushGuestRecent(4, [1, 2])).toEqual([4, 1, 2])
  })

  it('keeps at most 20 and ignores junk in storage', () => {
    const many = Array.from({ length: 25 }, (_, i) => i + 1)
    expect(pushGuestRecent(99, many)).toHaveLength(20)
    saveJson(RECENT_KEY, [5, 'x', 6])
    expect(readGuestRecent()).toEqual([5, 6])
  })
})
