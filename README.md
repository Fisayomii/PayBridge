# PayBridge

A Spring Boot modular monolith for learning:
local money transfer, cross-border settlement, and USD → NGN remittance.

## Run
Required environment (put them in `.env`, which is git-ignored):

| Variable | Purpose |
|----------|---------|
| `DB_USERNAME`, `DB_PASSWORD`, `DB_URL` | PostgreSQL |
| `PAYBRIDGE_JWT_SECRET` | HS256 signing key, at least 32 bytes (`openssl rand -base64 48`) |
| `PAYSTACK_SECRET_KEY` | Paystack key; also verifies webhook signatures |
| `PAYBRIDGE_BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | Creates the first admin on startup if none exists |
| `PAYBRIDGE_PAYMENTS_PROVIDER` | `paystack` (default) or `simulated` for offline demos |

    mvn spring-boot:run

## Module rule
A module may only use another module through its `api/` package.
Never call another module's repository or service directly.

## Modules
| Module       | Responsibility                                      |
|--------------|-----------------------------------------------------|
| shared       | Money value object, global errors, config, auditing |
| customer     | Customers, authentication, KYC tier                 |
| funding      | Wallet top-ups (Paystack checkout), adjustments     |
| account      | NGN and USD accounts, balances                      |
| ledger       | Double-entry bookkeeping                            |
| transfer     | Local account-to-account transfers                  |
| fx           | USD→NGN rates, quotes, rate locking                 |
| remittance   | Inbound USD paid out in NGN                         |
| settlement   | Cross-border settlement and reconciliation          |
| compliance   | AML, sanctions screening, limits                    |
| notification | Email/SMS alerts via events                         |

## Security
- Customers register at `POST /api/v1/auth/register` (an empty NGN wallet is opened, KYC tier 1) and log in at
  `POST /api/v1/auth/login` for a 15-minute JWT plus a single-use refresh token (`/auth/refresh` rotates it;
  replaying an old one revokes the whole session). 5 failed logins lock the account for 15 minutes.
- Every endpoint except register/login/refresh, provider webhooks and Swagger needs `Authorization: Bearer <token>`.
- Customers only ever see and move money from **their own** wallets; anything else is reported as not found.
- `/api/v1/admin/**` requires `ROLE_ADMIN` (KYC tier changes, suspensions, wallet freezes, adjustments).

## Money in: funding
Wallets start at zero. Money enters only through a balanced ledger posting:

| Channel | Endpoint | Posting |
|---------|----------|---------|
| Paystack checkout (card, transfer, USSD) | `POST /api/v1/fundings` returns `authorizationUrl` | `GL-NGN-PAYSTACK-COLLECTIONS -> wallet`, only after server-side verification of amount and currency |
| Admin adjustment (audited, reason required) | `POST /api/v1/admin/accounts/{wallet}/adjustments` | `GL-NGN-ADJUSTMENTS <-> wallet` |

Because of this, **the sum of every account balance (customer wallets and GL accounts) is always exactly zero**.
The integration tests assert it after every test.

## Transfer flows

| Flow | Endpoint | Fee (configurable) | Settles |
|------|----------|--------------------|---------|
| Wallet → wallet | `POST /api/v1/transfers/internal` | free | instantly, atomically (`201`) |
| Wallet → bank account | `POST /api/v1/transfers/external` | ₦10 ≤ ₦5k, ₦25 ≤ ₦50k, ₦50 above | asynchronously (`202`) |

Both need an `Idempotency-Key` header (unique per sending wallet) and accept either explicit destination
details or a saved `beneficiaryId` (`/api/v1/beneficiaries`; `saveBeneficiary: true` saves after sending).
`GET /api/v1/transfers/fees?type=EXTERNAL&amount=...` quotes the fee.

External payout postings:

    hold      wallet -> GL-NGN-PAYOUT-CLEARING (amount), wallet -> GL-NGN-FEE-INCOME (fee)   PENDING/PROCESSING
    success   GL-NGN-PAYOUT-CLEARING -> GL-NGN-PAYSTACK-SETTLEMENT                         SUCCESS
    failure   amount and fee returned to the wallet                                        FAILED
    reversal  GL-NGN-PAYSTACK-SETTLEMENT -> wallet, fee returned                           REVERSED

Final status arrives through the signed webhook `POST /api/v1/webhooks/paystack`, with reconciliation jobs
polling Paystack for stuck payouts and checkouts. An unknown outcome is never refunded or reported as success.

## Limits and controls
- **KYC tiers** (set by an admin after verification) drive single-transfer, daily (Lagos day, failed payouts
  excluded) and maximum-balance limits under `paybridge.compliance.tiers`; **velocity** is capped at 10 transfers
  per 10 minutes. Limits are checked while the sender's wallet row is locked, so concurrent requests cannot both
  slip under a limit.
- **Wallet status**: `FROZEN` (no customer debits or credits), `POST_NO_DEBIT` (credits only). Every change needs
  a reason and is kept in `account_status_history`. Refunds and confirmed top-ups still reach a frozen wallet.
- **Customer suspension** blocks login, revokes sessions, and blocks money movement immediately.

## Sandbox
Set `PAYBRIDGE_PAYMENTS_PROVIDER=simulated` for offline demos:
- `POST /api/v1/fundings/simulations/{reference}/pay` pays one of your checkouts.
- `POST /api/v1/admin/simulations/transfers/{reference}/webhook` drives a payout to success, failure or reversal.
- Magic beneficiary accounts: `9999999999` fails name enquiry, `8888888888` is rejected (refunded), and
  `7777777777` times out (stays PENDING).

## Tests
Integration tests run against a real PostgreSQL database `paybridge_test` (override with `TEST_DB_URL`),
using the `DB_USERNAME` / `DB_PASSWORD` from `.env`. Start from an empty database: the zero-sum balance check
assumes no unledgered balances exist.

    ./mvnw test
