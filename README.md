# BookMarked

**Books worth coming back to.**

A full-stack online bookstore for **paperback, hardcover and e-books**, built with Spring Boot and React.

| In the app | Means |
| --- | --- |
| **Bookmarks** | Wishlist: books saved for later |
| **Your Stack** | Shopping cart |
| **My Library** | Purchased e-books, readable in the browser |

> Status: **Phase 1 · Steps 1–3 done**: setup, authentication, catalog, Your Stack and Bookmarks.
> Next: Step 4, checkout with Razorpay (test mode), Cash on Delivery and stock reservation.

## Tech stack

| Layer | Choice |
| --- | --- |
| Frontend | React 19 (Vite), Tailwind CSS, Redux Toolkit + RTK Query, React Router |
| Backend | Spring Boot 3.5 (Java 21), Spring Security 6, Spring Data JPA, Bean Validation |
| Database | MySQL 8 with Flyway migrations |
| Auth | JWT access tokens + rotating refresh tokens in httpOnly cookies, BCrypt |
| Email | Spring Mail + Thymeleaf templates (Mailpit locally) |
| Testing | JUnit 5, Testcontainers (MySQL), Vitest |
| DevOps | Docker, docker-compose, GitHub Actions |

## Repository layout

```
bookmarked/
├─ bookmarked-api/        Spring Boot REST API
│  ├─ src/main/java/com/bookmarked/
│  │  ├─ auth/           login, register, sessions, JWT, password reset
│  │  ├─ account/        /api/account: me, change password, sessions, login history
│  │  ├─ user/           users, roles, permissions
│  │  ├─ mail/           async transactional email
│  │  ├─ common/         errors, rate limiting, helpers
│  │  ├─ config/         security, CORS, OpenAPI, typed properties
│  │  └─ seed/           first Super Admin
│  └─ src/main/resources/db/migration/   Flyway SQL (full schema in V1)
├─ bookmarked-web/        React app
│  └─ src/{app,features,components,pages,utils}
├─ docker-compose.yml
└─ .github/workflows/ci.yml
```

## Running locally

**Prerequisites:** Docker Desktop, Java 21, Node 20+ and Maven (or IntelliJ IDEA, which bundles Maven).

### Option A – everything in Docker

```bash
docker compose up --build
```

| What | URL |
| --- | --- |
| App | http://localhost:3000 |
| API + Swagger UI | http://localhost:8080/swagger-ui |
| Email inbox (Mailpit) | http://localhost:8025 |

A Super Admin is created on first start: `admin@bookmarked.local` / `Admin@12345` (change it in `.env`).

### Option B – day-to-day development

```bash
# 1. Database + email catcher
docker compose up -d mysql mailpit

# 2. API (http://localhost:8080)
cd bookmarked-api
mvn spring-boot:run          # or run BookmarkedApiApplication from IntelliJ

# 3. Web (http://localhost:5173, proxies /api to :8080)
cd bookmarked-web
npm install
npm run dev
```

To seed the Super Admin in this mode, set `SUPER_ADMIN_EMAIL` and `SUPER_ADMIN_PASSWORD` in the API's environment
(IntelliJ run configuration → Environment variables).

## Tests

```bash
cd bookmarked-api && mvn verify     # unit + Testcontainers integration tests (needs Docker running)
cd bookmarked-web && npm test       # Vitest
```

## What Step 1 includes

**Auth API (`/api/auth`)**

- Register with name, email, Indian mobile number and a strong password; verification email
- Verify email, resend verification
- Log in with **email or mobile number** in one field
- Short-lived JWT access token (15 min, 10 min for staff) + 7-day refresh token in an **httpOnly, SameSite=Strict cookie**
- **Refresh-token rotation with reuse detection**: replaying an old token revokes every session of that user
- Account **lockout** after 5 failed logins (15 minutes) with an email alert
- Forgot / reset password (single-use, 30-minute link; signs out all devices)
- New-device sign-in alert email

**Account API (`/api/account`)**

- `GET /me`, change password (keeps this device, signs out the rest)
- **Active sessions** list with "this device", log out one device or all others
- Last 10 sign-in attempts

**Security**

- Generic login errors and constant-time checks for unknown accounts (no account enumeration)
- Bucket4j rate limits on login, register, email actions and refresh
- Permission-based authorization (`@PreAuthorize("hasAuthority(...)")`), 3 staff roles + a read-only demo role seeded by Flyway
- CSRF defence for cookie endpoints: SameSite=Strict + required `X-Requested-With` header
- DTOs only, global JSON error handler, no stack traces to clients
- Only token hashes (SHA-256) are stored for refresh, verification and reset tokens

**Frontend**

- Login, Signup (live password checklist), Verify email, Forgot / Reset password
- My account and Security pages (sessions, change password, sign-in history)
- Access token kept in memory only; silent refresh on page load and automatic refresh + retry on 401
- Route guards for logged-in users and staff, `<Can permission="...">` component

## What Step 2 includes

**Catalog API (public)**

- `GET /api/books`: search + filters + sort + pagination in one endpoint
  (`q`, `category` with subcategories, `author`, `format`, `minPrice`, `maxPrice`, `minRating`, `inStock`, `featured`,
  `sort=relevance|price_asc|price_desc|newest|rating`)
- Search matches title, author and exact ISBN, plus **MySQL FULLTEXT** on title + description (prefix matching, relevance order)
- `GET /api/books/suggest` for search-as-you-type, `GET /api/books/{slug}`, `GET /api/categories` (cached tree)
- Books have **Paperback / Hardcover / E-book variants**, each with its own ISBN, MRP, price and stock
- Customers never see exact stock: `IN_STOCK`, `LOW_STOCK` (with "only N left"), `OUT_OF_STOCK` or `DIGITAL`

**Admin API** (permission-checked)

- Create / edit books with all formats in one form; formats removed from a book are deactivated, never deleted
- Hide / show and feature books; categories and subcategories (deactivate only)
- ISBN-10/13 checksum validation, unique ISBNs and slugs, price ≤ MRP
- Every stock change is written to the **inventory ledger** (`inventory_movements`)

**Frontend**

- Browse page with filters, sort and pagination kept in the URL; navbar search with suggestions
- Book page with format tabs, prices, discount and stock messages
- Admin: books table, book form (formats, authors, categories, preview), categories manager

In the `dev` profile, 16 sample books are added to an empty database (`app.seed.demo-catalog`). Their ISBNs are
checksum-valid but fictional.

## What Step 3 includes

**Your Stack (cart)**

- `GET /api/cart`, `POST /api/cart/items`, `PATCH|DELETE /api/cart/items/{id}`, `POST /api/cart/items/{id}/save-for-later`,
  `POST /api/cart/accept-prices`, `POST /api/cart/merge`, and a public `POST /api/cart/preview` for guests
- Prices are always read live on the server; one `PricingService` works out MRP total, discount, delivery
  (₹40 below ₹499, free above, none for e-book-only) and the total, and checkout will reuse it
- Rules: e-books are one copy and can't be bought twice; physical books max 10 and never more than in stock;
  hidden, sold-out or over-stock lines stay visible but block checkout; price changes since adding are flagged
- Guests keep their Stack in the browser; it merges into the account at login (quantities added and capped)
- Staff accounts can't shop

**Bookmarks (wishlist)**: `GET /api/bookmarks`, `GET /api/bookmarks/ids`, `PUT|DELETE /api/bookmarks/{bookId}` (idempotent)

**Discovery**: `GET /api/books/{slug}/related` (more by the author, similar by category), `GET /api/books/by-ids`,
and recently viewed (`/api/recently-viewed`, stored per user; guests keep it in the browser and it merges at login)

**Frontend**: Your Stack page, Bookmarks page with "Move to Your Stack", bookmark icons on every book,
full book page (Add to Stack, Buy now, Bookmark, related rows), home rows for recently viewed, top rated and e-books

## What Step 4 includes

**Addresses**: `GET|POST /api/account/addresses`, `PUT|DELETE /api/account/addresses/{id}`,
`PATCH /api/account/addresses/{id}/default` (up to 5 per customer, always one default)

**Checkout** (`POST /api/checkout/create-order`, `POST /api/checkout/verify`, `POST /api/checkout/{orderNumber}/retry`)

- From Your Stack or "Buy now" (one book, skips the Stack). Prices, stock and ownership are checked again on the server
- Stock is taken with one atomic `UPDATE … WHERE stock >= qty`, so two buyers can never get the same last copy
  (there's a test that races six buyers for one book)
- An `Idempotency-Key` header makes a double-click or network retry return the same order instead of a second one
- **Online (Razorpay test mode):** the server creates the Razorpay order and holds the stock for 15 minutes.
  The browser only ever gets the key id; the payment is confirmed only after checking the HMAC-SHA256 signature
  (`order_id|payment_id`, key secret) on the server, and the amount must match to the paisa
- **Webhook** `POST /api/webhooks/razorpay` (`payment.captured`, `order.paid`, `payment.failed`, `refund.processed`):
  signature checked over the raw body with the webhook secret; duplicate deliveries are ignored by event id; it confirms
  orders even if the customer closed the tab. Verify and webhook can arrive in any order and confirm exactly once
- Unpaid orders expire after 15 minutes (a job runs every minute) and the stock goes back. If money arrives after that,
  the order is confirmed if stock is still there, otherwise refunded automatically
- **Cash on Delivery:** physical books only, orders up to ₹5,000, verified email, at most 2 unpaid COD orders at a time
- On confirmation: e-books go straight to My Library, physical items are "Order placed", bought items leave Your Stack,
  every stock change is in the inventory ledger, and a confirmation email is sent

**Orders**: `GET /api/orders`, `GET /api/orders/{orderNumber}` (only your own)

**Frontend**: checkout page (address, payment method, review), Razorpay popup loaded only when paying,
order confirmation page, My orders with "Complete payment" for unpaid orders, saved addresses page

**Razorpay keys:** put `RAZORPAY_KEY_ID` and `RAZORPAY_KEY_SECRET` (test mode) in the root `.env`, which git ignores.
Without them, online payment answers "payments unavailable" and COD still works. Test cards and UPI ids are on
[Razorpay's test page](https://razorpay.com/docs/payments/payments/test-card-details/). The webhook needs a public URL
(for example an ngrok tunnel to port 8080) and `RAZORPAY_WEBHOOK_SECRET`; checkout works without it.

## What Step 5 includes (part 1)

**Fulfilment (staff, `/api/admin/orders`)**: search by order number, email or phone and filter by order or item status;
move physical items PLACED → PROCESSING → SHIPPED (courier, tracking number, expected date) → DELIVERED, one step at a
time (no skipping, no going back; repeating a step is a no-op); record COD cash collected after delivery. Every change
is in the item's history with the staff member who made it. The order becomes COMPLETED when every item is finished.

**Cancellation** (`POST /api/orders/{n}/cancel` for customers, `/api/admin/orders/{n}/cancel` for staff with a reason)

- Physical items still PLACED or PROCESSING; partial cancellation supported; e-books and shipped items can't be cancelled
- Stock goes back (CANCEL in the inventory ledger)
- Online orders are refunded through Razorpay what was paid for those items, plus the delivery fee once no physical
  item is left. COD orders have nothing to refund
- The order row is locked (`SELECT … FOR UPDATE`), so repeated or simultaneous requests can't refund twice
  (a test fires four cancels at once and checks Razorpay is called exactly once)

**Refunds**: every refund is recorded (PENDING → PROCESSED via Razorpay's `refund.processed` webhook). If Razorpay
refuses or is unreachable, the cancellation still stands, the refund is marked FAILED, staff are notified, and they can
retry from **Admin → Refunds**.

**Notifications**: in-app (bell with unread count, polled every 30 s; `/notifications` page) plus email for shipped,
delivered, cancelled and refund processed. Emails are sent only after the change is committed. Staff get new-order,
low-stock and failed-refund alerts.

**Invoices**: `GET /api/orders/{n}/invoice` (and the admin equivalent) returns a PDF built with Apache PDFBox.

**Frontend**: order tabs (All, In progress, Delivered, Cancelled), per-item progress with courier and tracking,
cancel with a refund preview, refunds and invoice download on the order page; admin Orders, order detail with
pack / ship / deliver / cancel actions, Refunds with retry, and live counts on the admin dashboard.

## What Step 5 includes (part 2): returns

- Physical books can be returned within 7 days of delivery, refund only (`POST /api/orders/{n}/items/{itemId}/return`)
  with a reason (damaged, wrong item, printing defect, not as described, changed my mind) and an optional comment
- Staff queue at **Admin → Returns** (`/api/admin/returns`): approve → picked up → received (restock, which puts the
  book back in stock with a RETURN ledger entry, or write off) → refunded; or decline with a reason the customer sees
- Refunds: online orders through Razorpay; Cash on Delivery orders to the UPI ID the customer gave, paid by staff
  who record the UPI reference
- Steps only go forward, repeats are harmless, and each step locks the order like cancellations do
- The customer sees "Return this book" with the deadline, then the return's progress on the order page, and is
  notified (in-app and email) at each step
- Return photos need an image host (Cloudinary) and will be added when one is set up

## What Step 6 includes: e-books

- **Uploads (staff, `PUT /api/admin/variants/{id}/ebook`)**: PDF only, up to 100 MB. The file type is checked from its
  first bytes and the whole PDF is opened (broken, empty or password-protected files are refused). A sample of the
  first pages (about a tenth, at most 10) is cut automatically; staff can replace it with their own. The page count is
  saved on the format
- **Storage**: a private folder (`STORAGE_DIR`, a Docker volume in compose) behind a small `FileStorage` interface with
  strict keys, so it can move to an R2/S3 bucket at deployment without other changes
- **My Library** (`/api/library`): owned e-books, most recently read first, with progress ("Page 45 of 320 · 14%")
- **Reading**: the browser asks for a signed link valid for 5 minutes (HMAC, like an S3 pre-signed URL), and the
  reader (react-pdf, loaded only on that page) opens it directly. Ownership is checked again when the file is served.
  Page navigation, zoom, dark mode, full screen and arrow keys; the position is saved and syncs across devices
- **Downloads**: 5 per book, counted with an atomic UPDATE and logged with the IP. Links are single use, and every
  page of the downloaded copy carries the buyer's name and email (footer and a faint diagonal mark, plus the PDF's
  properties)
- **Samples**: public at `/api/books/variants/{id}/sample`; "Read a free sample" on the book page; owners see "Read now"

## What Step 7 includes (part 1): reviews and the account dashboard

**Reviews** (`/api/books/{slug}/reviews`, `/api/reviews/{id}`)

- Only buyers: e-books as soon as they're bought, printed books once delivered. One review per customer per book
  (shared by all its formats); edit or delete your own
- Rating 1–5, optional title (100), comment 10–2000 characters; HTML is stripped before saving
- Book page: average, star distribution (click a bar to filter), sort by most helpful / newest / highest / lowest,
  "Verified purchase", "Helpful" votes (one each, not on your own), and Report
- Three reports hide a review automatically and alert moderators (**Admin → Reviews**, `REVIEW_MODERATE`), who
  restore or remove it. The book's average and count are recalculated in the same transaction as every change
- Reviews show "Hemant M.", never full surnames or emails; 5 review actions per minute per customer
- After delivery, customers get a "How was it?" notification linking to the reviews

**Account dashboard** (`/account`): a sidebar (a scrolling row on phones) with Overview, Profile, Orders, My Library,
Bookmarks, Addresses, My reviews, Notifications and Security. The overview comes from one call
(`GET /api/account/overview`): stats, continue reading, recent orders and things to do (verify email, finish a payment,
have a return ready for pickup, books waiting for a review). Profile: change your name, or your mobile number with
your password (it must not belong to another account).

## What Step 7 includes (part 2): staff, customers and the audit log

- **Staff accounts** (`/admin/staff`, Super Admin): add a person with a role and they get an
  email to set their own password (`/accept-invite`, link valid 48 hours). Nobody else ever knows it.
  Change role, block/unblock, sign out everywhere, resend the invite.
- **Guard rails**: you can't change, block or sign out yourself, and the last active Super Admin
  can't be demoted or blocked, so the store can't be locked out.
- **Immediate revocation**: role changes, blocks and "sign out everywhere" take effect on the next
  request, not when the 15-minute access token expires. A small cached session check (20 s, cleared
  on every revocation) rejects tokens whose session was ended (`401 SESSION_REVOKED`).
- **Customers** (`/admin/customers`): search by name, email or phone; filter active/blocked/deleted;
  a profile with orders, spend (net of refunds), returns, reviews, e-books, active sessions and recent sign-ins.
- **Audit log** (`/admin/audit`): every non-GET admin request is recorded automatically (who, what,
  where from, result), including refused attempts. Staff and customer actions record before/after values.
  Entries are append-only and survive a failed request (separate transaction).

## What Step 7 includes (part 3): inventory and account lifecycle

- **Inventory** (`/admin/inventory`): every printed edition with stock, copies held by orders
  awaiting payment, and sales in the last 30 days; low-stock and sold-out filters with counts.
  Restock or correct a count (corrections need a reason). One atomic, row-locked update that can't
  go below zero, an inventory ledger entry, and an audit entry with before/after stock.
  Low-stock alerts now link here.
- **Change email**: needs the password; the new address must click a 24-hour link, and the old
  address is told when the change is requested and when it happens. Afterwards every session ends.
- **Delete account**: password plus typing DELETE, refused while an order, return or refund is in
  progress. The account is anonymised ("Former customer", unusable email and phone, random password,
  blocked); addresses, Your Stack, Bookmarks, recently viewed, reading progress and notifications are
  deleted; orders and invoices are kept for tax records. The old email and phone can sign up again.

## Configuration

All settings come from environment variables; see [`.env.example`](.env.example).
Business rules (delivery fee, reservation time, return window…) live in
`bookmarked-api/src/main/resources/application.yml` under `app.business`.

**Production notes:** serve the frontend and API on the same site (e.g. `bookmarked.in` and `api.bookmarked.in`) so the
refresh cookie is first-party, set `COOKIE_SECURE=true`, a strong `JWT_SECRET`, and run behind a proxy that sets
`X-Forwarded-For` (the API trusts forwarded headers for client IPs).
