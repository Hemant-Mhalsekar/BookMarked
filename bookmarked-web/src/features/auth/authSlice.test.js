import reducer, {
  selectHasPermission,
  selectIsStaff,
  sessionEnded,
  sessionStarted,
  userUpdated,
} from './authSlice'

const user = {
  id: 1,
  fullName: 'Hemant M',
  email: 'hemant@example.com',
  accountType: 'STAFF',
  role: 'INVENTORY_MANAGER',
  permissions: ['BOOK_READ', 'BOOK_WRITE'],
}

describe('authSlice', () => {
  it('starts idle with no user', () => {
    expect(reducer(undefined, { type: '@@init' })).toEqual({ accessToken: null, user: null, status: 'idle' })
  })

  it('stores the session on login/refresh', () => {
    const state = reducer(undefined, sessionStarted({ accessToken: 'abc', user }))
    expect(state).toEqual({ accessToken: 'abc', user, status: 'ready' })
  })

  it('clears everything on logout but stays ready', () => {
    const loggedIn = reducer(undefined, sessionStarted({ accessToken: 'abc', user }))
    expect(reducer(loggedIn, sessionEnded())).toEqual({ accessToken: null, user: null, status: 'ready' })
  })

  it('updates the profile without touching the token', () => {
    const loggedIn = reducer(undefined, sessionStarted({ accessToken: 'abc', user }))
    const next = reducer(loggedIn, userUpdated({ ...user, fullName: 'New Name' }))
    expect(next.accessToken).toBe('abc')
    expect(next.user.fullName).toBe('New Name')
  })

  it('answers permission and staff checks', () => {
    const state = { auth: reducer(undefined, sessionStarted({ accessToken: 'abc', user })) }
    expect(selectIsStaff(state)).toBe(true)
    expect(selectHasPermission('BOOK_WRITE')(state)).toBe(true)
    expect(selectHasPermission('REFUND_ISSUE')(state)).toBe(false)
  })
})
