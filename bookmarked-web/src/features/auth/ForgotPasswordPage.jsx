import { useState } from 'react'
import { Link } from 'react-router-dom'
import AuthCard from '../../components/AuthCard'
import { Alert, SubmitButton, TextInput } from '../../components/ui'
import { parseApiError } from '../../utils/errors'
import { useForgotPasswordMutation } from './authApi'

export default function ForgotPasswordPage() {
  const [identifier, setIdentifier] = useState('')
  const [forgot, { isLoading, isSuccess, data, error }] = useForgotPasswordMutation()

  return (
    <AuthCard
      title="Forgot your password?"
      subtitle="Enter your email or mobile number and we'll email you a reset link."
      footer={<Link to="/login" className="link">Back to log in</Link>}
    >
      {isSuccess ? (
        <Alert type="success">{data?.message}</Alert>
      ) : (
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault()
            forgot(identifier.trim())
          }}
        >
          {error && <Alert type="error">{parseApiError(error).message}</Alert>}
          <TextInput label="Email or mobile number" value={identifier} autoComplete="username"
                     onChange={(e) => setIdentifier(e.target.value)} required />
          <SubmitButton loading={isLoading} disabled={!identifier.trim()}>Send reset link</SubmitButton>
        </form>
      )}
    </AuthCard>
  )
}
