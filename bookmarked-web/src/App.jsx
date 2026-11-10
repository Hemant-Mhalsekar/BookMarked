import { lazy, Suspense, useEffect } from 'react'
import { useSelector } from 'react-redux'
import { Outlet, Route, Routes } from 'react-router-dom'
import Navbar from './components/Navbar'
import ProtectedRoute, { GuestOnlyRoute } from './components/ProtectedRoute'
import AccountPage from './features/account/AccountPage'
import AccountLayout from './features/account/AccountLayout'
import AddressesPage from './features/account/AddressesPage'
import ProfilePage from './features/account/ProfilePage'
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
import AdminReviewsPage from './features/admin/AdminReviewsPage'
import AdminAuditPage from './features/admin/people/AdminAuditPage'
import AdminCustomerPage from './features/admin/people/AdminCustomerPage'
import AdminCustomersPage from './features/admin/people/AdminCustomersPage'
import AdminStaffPage from './features/admin/people/AdminStaffPage'
import AcceptInvitePage from './features/auth/AcceptInvitePage'
import ConfirmEmailChangePage from './features/auth/ConfirmEmailChangePage'
import GoodbyePage from './features/account/GoodbyePage'
import AdminInventoryPage from './features/admin/inventory/AdminInventoryPage'
import AdminOrderPage from './features/admin/orders/AdminOrderPage'
import AdminOrdersPage from './features/admin/orders/AdminOrdersPage'
import AdminRefundsPage from './features/admin/orders/AdminRefundsPage'
import AdminReturnsPage from './features/admin/orders/AdminReturnsPage'
import BookDetailsPage from './features/catalog/BookDetailsPage'
import BooksPage from './features/catalog/BooksPage'
import BookmarksPage from './features/bookmarks/BookmarksPage'
import LibraryPage from './features/library/LibraryPage'
import NotificationsPage from './features/notifications/NotificationsPage'
import MyReviewsPage from './features/reviews/MyReviewsPage'
import CheckoutPage from './features/checkout/CheckoutPage'
import OrderDetailPage from './features/orders/OrderDetailPage'
import OrdersPage from './features/orders/OrdersPage'
import SessionSync from './features/stack/SessionSync'
import StackPage from './features/stack/StackPage'
import AdminHomePage from './pages/AdminHomePage'
import HomePage from './pages/HomePage'
import NotFoundPage from './pages/NotFoundPage'
import { FullPageSpinner } from './components/ui'

// The PDF engine is large, so the reader pages load only when opened
const ReaderPage = lazy(() => import('./features/library/ReaderPage'))
const SampleReaderPage = lazy(() => import('./features/library/SampleReaderPage'))

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
        <Route path="read-sample/:variantId" element={<Suspense fallback={<FullPageSpinner />}><SampleReaderPage /></Suspense>} />
        <Route path="verify-email" element={<VerifyEmailPage />} />
        <Route path="confirm-email-change" element={<ConfirmEmailChangePage />} />
        <Route path="goodbye" element={<GoodbyePage />} />

        <Route element={<GuestOnlyRoute />}>
          <Route path="login" element={<LoginPage />} />
          <Route path="signup" element={<SignupPage />} />
          <Route path="forgot-password" element={<ForgotPasswordPage />} />
          <Route path="reset-password" element={<ResetPasswordPage />} />
          <Route path="accept-invite" element={<AcceptInvitePage />} />
        </Route>

        <Route element={<ProtectedRoute />}>
          <Route path="account" element={<AccountLayout />}>
            <Route index element={<AccountPage />} />
            <Route path="profile" element={<ProfilePage />} />
            <Route path="security" element={<SecurityPage />} />
            <Route element={<ProtectedRoute customerOnly />}>
              <Route path="addresses" element={<AddressesPage />} />
              <Route path="reviews" element={<MyReviewsPage />} />
            </Route>
          </Route>
          <Route path="notifications" element={<NotificationsPage />} />
        </Route>

        <Route element={<ProtectedRoute customerOnly />}>
          <Route path="bookmarks" element={<BookmarksPage />} />
          <Route path="library" element={<LibraryPage />} />
          <Route path="library/read/:variantId" element={<Suspense fallback={<FullPageSpinner />}><ReaderPage /></Suspense>} />
          <Route path="checkout" element={<CheckoutPage />} />
          <Route path="order-success/:orderNumber" element={<OrderDetailPage success />} />
          <Route path="orders" element={<OrdersPage />} />
          <Route path="orders/:orderNumber" element={<OrderDetailPage />} />
        </Route>

        <Route element={<ProtectedRoute staffOnly />}>
          <Route path="admin" element={<AdminLayout />}>
            <Route index element={<AdminHomePage />} />
            <Route element={<ProtectedRoute staffOnly permission="BOOK_READ" />}>
              <Route path="books" element={<AdminBooksPage />} />
              <Route path="categories" element={<AdminCategoriesPage />} />
              <Route path="inventory" element={<AdminInventoryPage />} />
            </Route>
            <Route element={<ProtectedRoute staffOnly permission="ORDER_READ" />}>
              <Route path="orders" element={<AdminOrdersPage />} />
              <Route path="orders/:orderNumber" element={<AdminOrderPage />} />
            </Route>
            <Route element={<ProtectedRoute staffOnly permission="STAFF_MANAGE" />}>
              <Route path="staff" element={<AdminStaffPage />} />
            </Route>
            <Route element={<ProtectedRoute staffOnly permission="USER_MANAGE" />}>
              <Route path="customers" element={<AdminCustomersPage />} />
              <Route path="customers/:id" element={<AdminCustomerPage />} />
            </Route>
            <Route element={<ProtectedRoute staffOnly permission="AUDIT_VIEW" />}>
              <Route path="audit" element={<AdminAuditPage />} />
            </Route>
            <Route element={<ProtectedRoute staffOnly permission="REVIEW_MODERATE" />}>
              <Route path="reviews" element={<AdminReviewsPage />} />
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
