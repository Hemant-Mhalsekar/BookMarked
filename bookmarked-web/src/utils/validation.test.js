import { isStrongPassword, PHONE_REGEX, validatePasswordPair, validateSignup } from './validation'
import { parseApiError } from './errors'

describe('password rules (mirror the backend)', () => {
  it.each(['Str0ng@Pass', 'Abcdef1!', 'My-Long_Passw0rd'])('accepts %s', (pw) => {
    expect(isStrongPassword(pw)).toBe(true)
  })
  it.each(['password', 'Password1', 'PASSWORD1!', 'Sh0rt!', 'alllower1!'])('rejects %s', (pw) => {
    expect(isStrongPassword(pw)).toBe(false)
  })
})

describe('phone rule', () => {
  it.each(['9876543210', '6000000000'])('accepts %s', (p) => expect(PHONE_REGEX.test(p)).toBe(true))
  it.each(['5876543210', '987654321', '+919876543210'])('rejects %s', (p) => expect(PHONE_REGEX.test(p)).toBe(false))
})

describe('validateSignup', () => {
  const good = {
    fullName: 'Hemant M',
    email: 'hemant@example.com',
    phone: '9876543210',
    password: 'Str0ng@Pass',
    confirmPassword: 'Str0ng@Pass',
  }
  it('passes a valid form', () => expect(validateSignup(good)).toEqual({}))
  it('flags each bad field', () => {
    const errors = validateSignup({ fullName: 'H', email: 'nope', phone: '123', password: 'weak', confirmPassword: 'x' })
    expect(Object.keys(errors).sort()).toEqual(['confirmPassword', 'email', 'fullName', 'password', 'phone'])
  })
  it('flags mismatched confirmation only', () => {
    expect(validatePasswordPair('Str0ng@Pass', 'Str0ng@Pas')).toEqual({ confirmPassword: 'Passwords do not match' })
  })
})

describe('parseApiError', () => {
  it('reads the backend error shape', () => {
    const err = { status: 409, data: { error: 'EMAIL_TAKEN', message: 'Taken', fieldErrors: { email: 'x' } } }
    expect(parseApiError(err)).toEqual({ code: 'EMAIL_TAKEN', message: 'Taken', fieldErrors: { email: 'x' } })
  })
  it('explains network failures', () => {
    expect(parseApiError({ status: 'FETCH_ERROR' }).code).toBe('NETWORK')
  })
  it('explains rate limiting', () => {
    expect(parseApiError({ status: 429, data: {} }).code).toBe('RATE_LIMITED')
  })
})
