import { useDispatch, useSelector } from 'react-redux'
import { selectAuthStatus, selectUser } from '../auth/authSlice'
import { guestAdd, guestRemove, guestSetQuantity, selectGuestCount, selectGuestItems } from './guestStackSlice'
import {
  useAcceptPricesMutation,
  useAddToStackMutation,
  usePreviewStackQuery,
  useRemoveStackItemMutation,
  useSaveForLaterMutation,
  useStackQuery,
  useUpdateStackItemMutation,
} from './stackApi'

/**
 * One interface to Your Stack for both guests (browser + /cart/preview) and
 * customers (server-side Stack). Staff accounts have no Stack.
 *
 * All actions return a promise that rejects with the RTK Query error.
 */
export function useStack() {
  const dispatch = useDispatch()
  const user = useSelector(selectUser)
  const status = useSelector(selectAuthStatus)
  const guestItems = useSelector(selectGuestItems)
  const guestCount = useSelector(selectGuestCount)

  const isStaff = user?.accountType === 'STAFF'
  const isCustomer = Boolean(user) && !isStaff
  const isGuest = status === 'ready' && !user

  const server = useStackQuery(undefined, { skip: !isCustomer })
  const preview = usePreviewStackQuery(guestItems, { skip: !isGuest || guestItems.length === 0 })

  const [addMut, addState] = useAddToStackMutation()
  const [updateMut] = useUpdateStackItemMutation()
  const [removeMut] = useRemoveStackItemMutation()
  const [saveMut] = useSaveForLaterMutation()
  const [acceptMut] = useAcceptPricesMutation()

  let view = null
  if (isCustomer) view = server.data ?? null
  else if (isGuest) view = guestItems.length === 0 ? EMPTY_VIEW : preview.data ?? null

  const count = isCustomer ? server.data?.totalQuantity ?? 0 : isGuest ? guestCount : 0

  return {
    view,
    count,
    isGuest,
    isCustomer,
    isStaff,
    isLoading: (isCustomer && server.isLoading) || (isGuest && guestItems.length > 0 && preview.isLoading) || status !== 'ready',
    isUpdating: server.isFetching || preview.isFetching || addState.isLoading,

    add: async (variant, quantity = 1) => {
      if (isCustomer) return addMut({ variantId: variant.id, quantity }).unwrap()
      dispatch(guestAdd({ variantId: variant.id, quantity, single: variant.format === 'EBOOK' }))
      return null
    },
    setQuantity: async (line, quantity) => {
      if (isCustomer) return updateMut({ itemId: line.itemId, quantity }).unwrap()
      dispatch(guestSetQuantity({ variantId: line.variantId, quantity }))
      return null
    },
    remove: async (line) => {
      if (isCustomer) return removeMut(line.itemId).unwrap()
      dispatch(guestRemove(line.variantId))
      return null
    },
    saveForLater: async (line) => (isCustomer ? saveMut(line.itemId).unwrap() : null),
    acceptPrices: async () => (isCustomer ? acceptMut().unwrap() : null),
  }
}

const EMPTY_VIEW = {
  lines: [],
  summary: {
    itemCount: 0, mrpTotal: 0, discount: 0, subtotal: 0, deliveryFee: 0, total: 0,
    freeDeliveryThreshold: 499, amountForFreeDelivery: 0,
  },
  totalQuantity: 0,
  checkoutBlocked: true,
  pricesChanged: false,
}
