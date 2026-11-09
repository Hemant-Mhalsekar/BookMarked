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

## Configuration

All settings come from environment variables; see [`.env.example`](.env.example).
Business rules (delivery fee, reservation time, return window…) live in
`bookmarked-api/src/main/resources/application.yml` under `app.business`.

**Production notes:** serve the frontend and API on the same site (e.g. `bookmarked.in` and `api.bookmarked.in`) so the
refresh cookie is first-party, set `COOKIE_SECURE=true`, a strong `JWT_SECRET`, and run behind a proxy that sets
`X-Forwarded-For` (the API trusts forwarded headers for client IPs).
