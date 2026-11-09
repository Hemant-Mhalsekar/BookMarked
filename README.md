# BookMarked

**Books worth coming back to.**

A full-stack online bookstore for **paperback, hardcover and e-books**, built with Spring Boot and React.

| In the app | Means |
| --- | --- |
| **Bookmarks** | Wishlist: books saved for later |
| **Your Stack** | Shopping cart |
| **My Library** | Purchased e-books, readable in the browser |

> Status: **Phase 1 · Step 1 done**: project setup and complete authentication.
> Next: Step 2, books, variants, categories, search and admin book management.

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

## Configuration

All settings come from environment variables; see [`.env.example`](.env.example).
Business rules (delivery fee, reservation time, return window…) live in
`bookmarked-api/src/main/resources/application.yml` under `app.business`.

**Production notes:** serve the frontend and API on the same site (e.g. `bookmarked.in` and `api.bookmarked.in`) so the
refresh cookie is first-party, set `COOKIE_SECURE=true`, a strong `JWT_SECRET`, and run behind a proxy that sets
`X-Forwarded-For` (the API trusts forwarded headers for client IPs).
