import { useEffect } from 'react'
import { useSelector } from 'react-redux'
import { Outlet, Route, Routes } from 'react-router-dom'
import Navbar from './components/Navbar'
import ProtectedRoute, { GuestOnlyRoute } from './components/ProtectedRoute'
import AccountPage from './features/account/AccountPage'
import SecurityPage from './features/account/SecurityPage'
import { useRefreshMutation } from './features/auth/authApi'
import { selectAuthStatus } from './features/auth/authSlice'
import ForgotPasswordPage from './features/auth/ForgotPasswordPage'
import LoginPage from './features/auth/LoginPage'
import ResetPasswordPage from './features/auth/ResetPasswordPage'
import SignupPage from './features/auth/SignupPage'
import VerifyEmailPage from './features/auth/VerifyEmailPage'
import AdminHomePage from './pages/AdminHomePage'
import HomePage from './pages/HomePage'
import NotFoundPage from './pages/NotFoundPage'

/** On first load, trade the httpOnly refresh cookie (if any) for an access token. */
function useSessionBootstrap() {
  const status = useSelector(selectAuthStatus)
  const [refresh] = useRefreshMutation()
  useEffect(() => {
    if (status === 'idle') refresh()
  }, [status, refresh])
}

function Layout() {
  return (
    <div className="min-h-screen">
      <Navbar />
      <main>
        <Outlet />
      </main>
    </div>
  )
}

export default function App() {
  useSessionBootstrap()
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="verify-email" element={<VerifyEmailPage />} />

        <Route element={<GuestOnlyRoute />}>
          <Route path="login" element={<LoginPage />} />
          <Route path="signup" element={<SignupPage />} />
          <Route path="forgot-password" element={<ForgotPasswordPage />} />
          <Route path="reset-password" element={<ResetPasswordPage />} />
        </Route>

        <Route element={<ProtectedRoute />}>
          <Route path="account" element={<AccountPage />} />
          <Route path="account/security" element={<SecurityPage />} />
        </Route>

        <Route element={<ProtectedRoute staffOnly />}>
          <Route path="admin" element={<AdminHomePage />} />
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
