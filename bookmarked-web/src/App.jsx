import { useEffect } from 'react'
import { useSelector } from 'react-redux'
import { Outlet, Route, Routes } from 'react-router-dom'
import Navbar from './components/Navbar'
import ProtectedRoute, { GuestOnlyRoute } from './components/ProtectedRoute'
import AccountPage from './features/account/AccountPage'
import AddressesPage from './features/account/AddressesPage'
import SecurityPage from './features/account/SecurityPage'
import { useRefreshMutation } from './features/auth/authApi'
import { selectAuthStatus } from './features/auth/authSlice'
import ForgotPasswordPage from './features/auth/ForgotPasswordPage'
import LoginPage from './features/auth/LoginPage'
import ResetPasswordPage from './features/auth/ResetPasswordPage'
import SignupPage from './features/auth/SignupPage'
import VerifyEmailPage from './features/auth/VerifyEmailPage'
import AdminBookFormPage from './features/admin/AdminBookFormPage'
import AdminBooksPage from './features/admin/AdminBooksPage'
import AdminCategoriesPage from './features/admin/AdminCategoriesPage'
import AdminLayout from './features/admin/AdminLayout'
import AdminOrderPage from './features/admin/orders/AdminOrderPage'
import AdminOrdersPage from './features/admin/orders/AdminOrdersPage'
import AdminRefundsPage from './features/admin/orders/AdminRefundsPage'
import AdminReturnsPage from './features/admin/orders/AdminReturnsPage'
import BookDetailsPage from './features/catalog/BookDetailsPage'
import BooksPage from './features/catalog/BooksPage'
import BookmarksPage from './features/bookmarks/BookmarksPage'
import NotificationsPage from './features/notifications/NotificationsPage'
import CheckoutPage from './features/checkout/CheckoutPage'
import OrderDetailPage from './features/orders/OrderDetailPage'
import OrdersPage from './features/orders/OrdersPage'
import SessionSync from './features/stack/SessionSync'
import StackPage from './features/stack/StackPage'
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
      <SessionSync />
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
        <Route path="books" element={<BooksPage />} />
        <Route path="book/:slug" element={<BookDetailsPage />} />
        <Route path="stack" element={<StackPage />} />
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
          <Route path="notifications" element={<NotificationsPage />} />
        </Route>

        <Route element={<ProtectedRoute customerOnly />}>
          <Route path="bookmarks" element={<BookmarksPage />} />
          <Route path="checkout" element={<CheckoutPage />} />
          <Route path="order-success/:orderNumber" element={<OrderDetailPage success />} />
          <Route path="orders" element={<OrdersPage />} />
          <Route path="orders/:orderNumber" element={<OrderDetailPage />} />
          <Route path="account/addresses" element={<AddressesPage />} />
        </Route>

        <Route element={<ProtectedRoute staffOnly />}>
          <Route path="admin" element={<AdminLayout />}>
            <Route index element={<AdminHomePage />} />
            <Route element={<ProtectedRoute staffOnly permission="BOOK_READ" />}>
              <Route path="books" element={<AdminBooksPage />} />
              <Route path="categories" element={<AdminCategoriesPage />} />
            </Route>
            <Route element={<ProtectedRoute staffOnly permission="ORDER_READ" />}>
              <Route path="orders" element={<AdminOrdersPage />} />
              <Route path="orders/:orderNumber" element={<AdminOrderPage />} />
            </Route>
            <Route element={<ProtectedRoute staffOnly permission="RETURN_MANAGE" />}>
              <Route path="returns" element={<AdminReturnsPage />} />
            </Route>
            <Route element={<ProtectedRoute staffOnly permission="REFUND_ISSUE" />}>
              <Route path="refunds" element={<AdminRefundsPage />} />
            </Route>
            <Route element={<ProtectedRoute staffOnly permission="BOOK_WRITE" />}>
              <Route path="books/new" element={<AdminBookFormPage />} />
              <Route path="books/:id/edit" element={<AdminBookFormPage />} />
            </Route>
          </Route>
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
