import { Link } from 'react-router-dom'
import AuthCard from '../../components/AuthCard'

export default function GoodbyePage() {
  return (
    <AuthCard title="Your account has been deleted" subtitle="Thanks for reading with us. We've emailed you a confirmation.">
      <Link to="/" className="btn-primary w-full">Back to the store</Link>
    </AuthCard>
  )
}
