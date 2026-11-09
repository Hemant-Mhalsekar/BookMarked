import { useEffect, useRef } from 'react'
import { useDispatch, useSelector } from 'react-redux'
import { removeKey } from '../../utils/storage'
import { selectUser } from '../auth/authSlice'
import { readGuestRecent, RECENT_KEY, useMergeRecentMutation } from '../catalog/recentlyViewed'
import { guestClear, selectGuestItems } from './guestStackSlice'
import { useMergeStackMutation } from './stackApi'

/**
 * When a customer logs in (or signs up and logs in), fold what they did as a guest
 * into their account: the guest Stack and the recently viewed list. Runs once
 * per login; renders nothing.
 */
export default function SessionSync() {
  const dispatch = useDispatch()
  const user = useSelector(selectUser)
  const guestItems = useSelector(selectGuestItems)
  const [mergeStack] = useMergeStackMutation()
  const [mergeRecent] = useMergeRecentMutation()
  const syncedFor = useRef(null)

  useEffect(() => {
    if (!user || user.accountType === 'STAFF' || syncedFor.current === user.id) return
    syncedFor.current = user.id

    if (guestItems.length > 0) {
      mergeStack(guestItems)
        .unwrap()
        .then(() => dispatch(guestClear()))
        .catch(() => {
          // keep the guest items; they'll be offered again next login
          syncedFor.current = null
        })
    }
    const recent = readGuestRecent()
    if (recent.length > 0) {
      mergeRecent(recent).unwrap().then(() => removeKey(RECENT_KEY)).catch(() => {})
    }
  }, [user, guestItems, mergeStack, mergeRecent, dispatch])

  useEffect(() => {
    if (!user) syncedFor.current = null
  }, [user])

  return null
}
