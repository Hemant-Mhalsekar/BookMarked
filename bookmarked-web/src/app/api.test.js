import { makeStore } from './store'
import { sessionStarted } from '../features/auth/authSlice'
import { accountApi } from '../features/account/accountApi'

const json = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const user = { id: 1, fullName: 'Hemant', accountType: 'CUSTOMER', role: 'CUSTOMER', permissions: [] }

describe('automatic token refresh', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('refreshes once on a 401 and replays the request', async () => {
    const fetchMock = vi.fn(async (input) => {
      const req = input instanceof Request ? input : new Request(input)
      const auth = req.headers.get('Authorization')
      if (req.url.endsWith('/auth/refresh')) {
        expect(req.headers.get('X-Requested-With')).toBe('XMLHttpRequest')
        return json(200, { accessToken: 'fresh', expiresIn: 900, user })
      }
      if (req.url.endsWith('/account/me')) {
        return auth === 'Bearer fresh' ? json(200, user) : json(401, { error: 'TOKEN_EXPIRED' })
      }
      return json(404, {})
    })
    vi.stubGlobal('fetch', fetchMock)

    const store = makeStore()
    store.dispatch(sessionStarted({ accessToken: 'stale', user }))
    const result = await store.dispatch(accountApi.endpoints.me.initiate())

    expect(result.data).toEqual(user)
    expect(store.getState().auth.accessToken).toBe('fresh')
    const urls = fetchMock.mock.calls.map(([r]) => (r instanceof Request ? r.url : String(r)))
    expect(urls.filter((u) => u.endsWith('/auth/refresh'))).toHaveLength(1)
    expect(urls.filter((u) => u.endsWith('/account/me'))).toHaveLength(2)
  })

  it('logs out locally when the refresh fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input) => {
      const req = input instanceof Request ? input : new Request(input)
      return req.url.endsWith('/auth/refresh')
        ? json(401, { error: 'SESSION_REVOKED' })
        : json(401, { error: 'TOKEN_EXPIRED' })
    }))

    const store = makeStore()
    store.dispatch(sessionStarted({ accessToken: 'stale', user }))
    const result = await store.dispatch(accountApi.endpoints.me.initiate())

    expect(result.error.status).toBe(401)
    expect(store.getState().auth).toEqual({ accessToken: null, user: null, status: 'ready' })
  })
})
