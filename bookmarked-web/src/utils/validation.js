// Mirrors the backend rules in AuthDtos.java so users see errors before submitting.

export const NAME_REGEX = /^[\p{L} .'-]{2,60}$/u
export const PHONE_REGEX = /^[6-9]\d{9}$/
export const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export const passwordChecks = [
  { id: 'length', label: '8–64 characters', test: (p) => p.length >= 8 && p.length <= 64 },
  { id: 'lower', label: 'A lowercase letter', test: (p) => /[a-z]/.test(p) },
  { id: 'upper', label: 'An uppercase letter', test: (p) => /[A-Z]/.test(p) },
  { id: 'digit', label: 'A number', test: (p) => /\d/.test(p) },
  { id: 'special', label: 'A special character', test: (p) => /[^A-Za-z0-9]/.test(p) },
]

export const isStrongPassword = (p = '') => passwordChecks.every((c) => c.test(p))

export function validateSignup({ fullName, email, phone, password, confirmPassword }) {
  const errors = {}
  if (!NAME_REGEX.test(fullName.trim())) errors.fullName = 'Enter your name (2–60 letters)'
  if (!EMAIL_REGEX.test(email.trim())) errors.email = 'Enter a valid email'
  if (!PHONE_REGEX.test(phone.trim())) errors.phone = 'Enter a valid 10-digit mobile number'
  if (!isStrongPassword(password)) errors.password = 'Password does not meet all the rules below'
  if (password !== confirmPassword) errors.confirmPassword = 'Passwords do not match'
  return errors
}

export function validatePasswordPair(password, confirmPassword) {
  const errors = {}
  if (!isStrongPassword(password)) errors.password = 'Password does not meet all the rules below'
  if (password !== confirmPassword) errors.confirmPassword = 'Passwords do not match'
  return errors
}

export const PINCODE_REGEX = /^\d{6}$/

/** Mirrors AddressService.AddressRequest. */
export function validateAddress({ fullName, phone, line1, line2 = '', city, state, pincode }) {
  const errors = {}
  if (!NAME_REGEX.test(fullName.trim())) errors.fullName = "Enter the recipient's name"
  if (!PHONE_REGEX.test(phone.trim())) errors.phone = 'Enter a valid 10-digit mobile number'
  if (!line1.trim()) errors.line1 = 'Enter the address'
  else if (line1.trim().length > 120) errors.line1 = 'Keep it under 120 characters'
  if (line2.trim().length > 120) errors.line2 = 'Keep it under 120 characters'
  if (city.trim().length < 2 || city.trim().length > 60) errors.city = 'Enter the city'
  if (state.trim().length < 2 || state.trim().length > 60) errors.state = 'Enter the state'
  if (!PINCODE_REGEX.test(pincode.trim())) errors.pincode = 'Enter a 6-digit pincode'
  return errors
}
