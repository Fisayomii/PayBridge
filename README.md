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
| `PAYBRIDGE_FX_FEED_ENABLED` | `true` (default): live USD/NGN rate from open.er-api.com. `false` to use the stored or configured rate offline |
| `PAYBRIDGE_KYC_PROVIDER` | `simulated` (default) or `dojah` for real BVN lookups against Dojah's sandbox |
| `DOJAH_APP_ID`, `DOJAH_SECRET_KEY` | Dojah sandbox credentials (only with `PAYBRIDGE_KYC_PROVIDER=dojah`) |
| `PAYBRIDGE_OFAC_ENABLED` | `true` (default): screen remittances against the US Treasury OFAC SDN list. If the list cannot be loaded, every remittance goes to review |
| `PAYBRIDGE_SANCTIONS_CACHE_DIR` | Where the last downloaded OFAC list is kept (default `./data/sanctions`, git-ignored) |

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
| transfer     | Local account-to-account and bank transfers         |
| fx           | USD→NGN rates, quotes, rate locking, deviation guard |
| remittance   | Inbound USD from partners paid out in NGN, review queue |
| settlement   | Prefunds, FX deals and P&L, integrity checks, reconciliation |
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

---

## Remittance and settlement

> PayBridge pays out money sent from abroad (USD → NGN) and settles it. Everything below runs end to end with
> simulated providers, so it can be demoed offline on Swagger. The regulatory notes summarise public rules for
> planning. They are not legal advice: confirm them with a lawyer and the current CBN circulars before
> handling real customer money.

### What the three modules do

| Module | Question it answers | Example |
|--------|---------------------|---------|
| **fx** | "How many naira is $100 worth, and for how long is that price honoured?" | At a ₦1,550 mid rate: quote `$100 → ₦152,675.00`, valid 10 minutes, usable once |
| **remittance** | "Money was sent from abroad. Get it to the right Nigerian recipient, once, legally." | Partner sends $100 for wallet `9123456789`; the wallet is credited instantly |
| **settlement** | "Is the money the ledger says we hold really there? Does the provider agree with us?" | Book partner wires and the daily FX sale, run the integrity checks, reconcile payouts against the provider |

### The business model: payout partner (B2B)

PayBridge is the **Nigerian payout partner** for licensed money transfer operators abroad. The partner
collects from the sender, does the sender's KYC, prefunds a USD account with us, and calls our API.

| | **Payout partner (B2B), what we built** | Direct to senders abroad (B2C), later |
|---|---|---|
| Who pays in | Licensed operators abroad prefund a USD account with us | Individuals in the US/UK pay by card or bank transfer |
| Licences | Nigerian side only: a CBN IMTO licence, or acting as the payout partner of a licensed IMTO or bank | Nigerian side **plus** a licence in every sending country (US: FinCEN MSB registration and state licences; UK: FCA) |
| Fraud risk | Low. The partner did sender KYC and paid us before we pay out | High. Card chargebacks up to ~120 days later; US ACH "unauthorised" returns up to 60 days |
| Sender KYC | The partner's job; the data travels with each remittance (travel rule) | Ours |

Everything built here (quotes, screening, payout, settlement) would be reused by B2C, which only adds a sender
app and a card/bank collection provider.

### Rules the design follows

- **Licence.** Paying out international remittances in Nigeria needs a CBN International Money Transfer
  Operator (IMTO) licence, or a contract under which we act as a licensed IMTO's or bank's payout partner. The
  CBN's 2024 revised IMTO guidelines limit IMTOs to **inbound** transfers and let them price at the prevailing
  market rate. Outbound remittance (NGN → abroad) is therefore out of scope.
- **Travel rule (FATF Recommendation 16).** Every remittance must carry the sender's name, country, ID number
  and address, plus the recipient's name and account. The API refuses a remittance that is missing any of them.
- **AML reporting (Money Laundering (Prevention and Prohibition) Act 2022).** Currency transaction reports to the
  NFIU (on its goAML portal) for transactions of ₦5m or more for individuals and ₦10m or more for companies.
  Suspicious transaction reports at any amount. Records kept for at least 5 years.
- **Sanctions.** Screen both sender and recipient against OFAC SDN, UN, UK, EU and Nigerian sanctions lists,
  plus politically exposed persons (PEPs).
- **Data protection.** Nigeria Data Protection Act 2023 (registration with the NDPC). GDPR also applies if
  senders are in the UK or EU.

### How a remittance works

    Example at a mid rate of 1550.00 (the live feed sets the real one):

    Partner                          PayBridge
    -------                          ---------
    POST /partner/quotes  ($100) ->  rate = mid 1550.00 - 1.5% spread = 1526.75; NGN 152,675.00; fee $3; expires in 10 min
    POST /partner/remittances    ->  1. lock and consume the quote (once only, not expired, this partner's)
         + Idempotency-Key           2. compliance screening -> CLEAR / REVIEW / BLOCK
         + travel-rule data          3. BLOCK  -> REJECTED, nothing charged
                                     4. take $103 from the partner's prefund (422 INSUFFICIENT_PREFUND if short)
                                     5. CLEAR  -> credit the wallet NGN 152,675.00 now -> COMPLETED (+ credit alert email)
                                        REVIEW -> PENDING_REVIEW; an admin approves (credit) or rejects (refund $103)

Screening rules (`paybridge.compliance.remittance`):

| Rule | Decision |
|------|----------|
| Sender country under comprehensive sanctions (`KP`, `IR`, `SY`, `CU`) | **BLOCK** |
| Purpose contains a blocked keyword (`FRAUD`) | **BLOCK** |
| Sender or recipient name matches the watchlist (word order and case ignored) | REVIEW |
| Amount at or above USD 3,000 | REVIEW |
| Recipient name shares no word with the name on the wallet | REVIEW |

A possible match is **held, not rejected**: fuzzy name hits are mostly false positives, and an analyst
decides. The recipient wallet must exist, be NGN, not be frozen, and stay under its KYC tier's maximum balance.
If that fails when a remittance is created, the partner gets a 422 and nothing is charged. If it fails at
approval, the remittance is FAILED and the partner is refunded.

### How the money moves

The ledger is now **multi-currency**. Each currency must balance on its own, so the old invariant "the sum
of every balance is zero" became "zero **per currency**". The tests assert it after every test. New accounts:

| Account | Meaning |
|---------|---------|
| `PARTNER-USD-nnnnnn` (type `PARTNER`) | A partner's prefund, which we owe back to them. Can never go negative |
| `GL-USD-NOSTRO` | USD we hold at our bank |
| `GL-USD-REMITTANCE-CLEARING` | Partner money taken for remittances waiting for review |
| `GL-USD-FX-POSITION` / `GL-NGN-FX-POSITION` | USD bought from partners / NGN paid to recipients, not yet converted at the bank |
| `GL-USD-REMITTANCE-FEE-INCOME` | Our USD fee |
| `GL-NGN-NOSTRO` | NGN we hold at our bank |
| `GL-NGN-FX-PNL` | Realised gain (positive) or loss on FX deals |

Postings (`from -> to`):

    prefund booked        GL-USD-NOSTRO              -> PARTNER-USD                   amount of the wire
    remittance accepted   PARTNER-USD                -> GL-USD-REMITTANCE-CLEARING    amount + fee
    paid out              GL-USD-REMITTANCE-CLEARING -> GL-USD-FX-POSITION            amount
                          GL-USD-REMITTANCE-CLEARING -> GL-USD-REMITTANCE-FEE-INCOME  fee
                          GL-NGN-FX-POSITION         -> recipient wallet              amount x locked rate
    rejected / failed     GL-USD-REMITTANCE-CLEARING -> PARTNER-USD                   amount + fee (full refund)
    daily FX deal         GL-USD-FX-POSITION         -> GL-USD-NOSTRO                 all open USD, sold at the bank
                          GL-NGN-NOSTRO              -> GL-NGN-FX-POSITION            NGN the bank paid us
                          GL-NGN-FX-POSITION        <-> GL-NGN-FX-PNL                 what is left = realised gain/loss

After an FX deal both FX position accounts are exactly zero. Example: we paid recipients 1526.75 per dollar;
the bank buys our dollars at 1550.00; the 1.5% spread (₦2,325 on $100) lands in `GL-NGN-FX-PNL` as profit. If
the market had moved against us overnight, it would show there as a loss.

### What settlement does

| Job | Endpoint (admin) | What it proves |
|-----|------------------|----------------|
| Book a partner's wire | `POST /api/v1/admin/settlement/prefunds` | Partner balances are backed by USD at our bank. Idempotent on the bank's reference: a wire can never be booked twice |
| FX position | `GET /api/v1/admin/settlement/fx-position` | Open USD, the NGN paid out against it, average rate, and unrealised gain/loss at today's mid rate |
| FX deal | `POST /api/v1/admin/settlement/fx-deals` | Sells the whole open position at the bank's executed rate and books the realised gain/loss |
| Trial balance | `GET /api/v1/admin/settlement/trial-balance` | Every account, grouped by currency; each currency totals exactly zero |
| Integrity check | `GET /api/v1/admin/settlement/integrity-check` | Zero per currency; no overdrawn wallets; payout clearing = bank payouts in flight; remittance clearing = remittances in review; no open breaks |
| Payout reconciliation | `POST /api/v1/admin/settlement/reconciliations/payouts?date=` | Every bank payout of that Lagos day is checked against the provider. Each disagreement becomes a **break** |
| Breaks | `GET /breaks?status=OPEN`, `POST /breaks/{id}/resolve` | `ACCEPT_PROVIDER` applies the provider's status through the normal payout state machine, refunds included. `CLOSED_WITH_NOTE` moves no money |

A nightly job (00:30 Lagos) reconciles yesterday's payouts and runs the integrity check. Switch it off with
`PAYBRIDGE_SETTLEMENT_NIGHTLY_ENABLED=false`.

### Third parties needed for production

Each one sits behind an interface with a simulated implementation, as Paystack does.

| Need | Why | In this build | Options to evaluate for production |
|------|-----|---------------|-----------------------------------|
| **Licensed partner or IMTO licence** | Legal right to pay out remittances | n/a | A licensed IMTO or bank that sponsors you, or your own licence |
| **Partner integrations** | Who sends the remittances | Partners are admin-created logins | Licensed operators sending to Nigeria |
| **USD bank account** | Partners prefund here; the USD is sold from here | Admin books wires by hand (`/prefunds`) | A Nigerian bank domiciliary account, or a cross-border provider such as Fincra, Flutterwave, Thunes, TerraPay or Onafriq |
| **FX liquidity** | Turns USD into the NGN we pay out | Admin books the deal by hand (`/fx-deals`) | An authorised dealer bank on the official market (NFEM), or the provider above |
| **FX rate feed** | Prices quotes | **Live: open.er-api.com** (a reference rate), with admin override | The dealer bank's executable rate API, or the CBN/FMDQ published NFEM rate |
| **NGN payout rail** | Bank payouts | **Paystack, already integrated** | Add a second rail (for example Flutterwave) behind `TransferGateway` for failover |
| **Sanctions and PEP screening** | Required on every sender and recipient | **Live: OFAC SDN list**, plus a configurable watchlist | Add UN, UK, EU and Nigerian lists and PEPs (OpenSanctions bundles them), or a vendor such as ComplyAdvantage, LSEG World-Check or Dow Jones |
| **Identity verification** | Raises KYC tiers | **Dojah sandbox** (BVN → tier 2), or simulated | Dojah live keys; add NIN and a selfie check for tier 3 |
| **Bank statements** | Reconcile the nostro accounts | Not built | MT940 / CAMT.053 / CSV export or a host-to-host feed from the bank |
| **ShedLock** (a library) | Stops scheduled jobs running on every instance | Not needed on one instance | `net.javacrumbs.shedlock` with the JDBC provider |

### Live integrations (sandbox)

Four real third parties are wired in. Each falls back to a simulated version when switched off.

| | Provider | What it does in PayBridge | Endpoint |
|---|---|---|---|
| 1 | **ExchangeRate-API open feed** (open.er-api.com, free, no key) | Polls USD/NGN on startup and every 30 minutes. Every tick passes the 10% deviation guard; a refused tick raises a `RATE ALERT` log and keeps the last good rate. If no fresh rate has been stored for 6 hours, **quoting pauses** (503 `RATE_UNAVAILABLE`) rather than pricing on old data | `GET /api/v1/fx/rates` (`source`, `asOf`, `stale`) |
| 2 | **Paystack** (`GET /balance`, test key) | Liquidity: our Paystack NGN balance against the bank payouts already in flight. `OK`, `LOW` (headroom under ₦100,000) or `INSUFFICIENT` (some in-flight payouts will fail) | `GET /api/v1/admin/settlement/liquidity` |
| 4 | **US Treasury OFAC SDN list** (public, no key; about 19,500 names and 20,000 aliases) | Remittance screening of sender, recipient and wallet holder. Accents, punctuation, case, word order and filler words (BIN, AL, ABU...) are ignored, and one-letter spelling variants still match (Osama ~ Usama, Laden ~ Ladin). A hit goes to **review** with the listed name, sanctions programme, date of birth and entry number for the analyst. Downloaded at startup and daily, and cached on disk so a restart does not need OFAC. If the list is not loaded, screening **fails closed** | `GET /api/v1/admin/compliance/sanctions/status`, `/screen?name=`, `POST /refresh` |
| 3 | **Dojah** (BVN lookup, sandbox) | Self-service KYC: the customer submits a BVN; if the name on it matches their profile, they move to **tier 2** (higher limits). The raw BVN is never stored, only a masked copy and an HMAC, and one BVN can verify only one profile. 5 failed attempts a day, then 429 | `POST /api/v1/kyc/bvn`, `GET /api/v1/kyc` |

Paystack's transfer, name enquiry and checkout calls were already real (see Money in and Transfer flows).

**1. Live rate feed.** Nothing to set up: it is on by default. To check it:
   - Start the app and look for `USD/NGN mid rate ... from OPEN_ER_API` in the log.
   - `GET /api/v1/fx/rates` should show `"source": "OPEN_ER_API"` and `"stale": false`.
   - To work offline, set `PAYBRIDGE_FX_FEED_ENABLED=false`.

**2. Paystack balance.**
   - Set `PAYBRIDGE_PAYMENTS_PROVIDER=paystack` and `PAYSTACK_SECRET_KEY=sk_test_...` (dashboard → Settings → API Keys & Webhooks, in Test mode).
   - Then `GET /api/v1/admin/settlement/liquidity` shows `"provider": "PAYSTACK"` and your test balance.
   - In `simulated` mode it shows a fixed ₦2,000,000 (`paybridge.settlement.liquidity.simulated-balance`).

**3. Dojah BVN verification.**
   1. Sign up at [app.dojah.io](https://app.dojah.io) and create an app in the dashboard.
   2. Copy the app's **App ID** and its **sandbox secret key** (sandbox calls are free and never charge your wallet).
   3. In `.env`:

          PAYBRIDGE_KYC_PROVIDER=dojah
          DOJAH_APP_ID=<your app id>
          DOJAH_SECRET_KEY=<your sandbox secret key>

   4. See which name Dojah's sandbox returns for its test BVN `22222222222`:

          curl "https://sandbox.dojah.io/api/v1/kyc/bvn/full?bvn=22222222222" -H "AppId: <app id>" -H "Authorization: <secret key>"

   5. Restart the app and register a customer whose full name contains that `first_name` and `last_name`.
   6. As that customer, `POST /api/v1/kyc/bvn` with `{ "bvn": "22222222222" }` returns **VERIFIED**, tier 1 → 2. `GET /api/v1/auth/me` shows `kycTier: 2`.
   7. Register a customer with a different name and try the same BVN: **FAILED**. If that customer's name doesn't match, the reason is "does not match your profile name". If the BVN is already verified for the first customer, the reason is "already linked to another PayBridge profile".
   - The sandbox's test BVN `22222222222` belongs to **John Doe Adamu** (DOB 2000-05-01). Register "John Adamu" to see it verify.
   - With `PAYBRIDGE_KYC_PROVIDER=simulated`, every BVN belongs to "Ada Obi" and `00000000000` does not exist.
   - The test BVN can verify only one profile per database, so use a fresh database to run the demo again.

**4. OFAC sanctions screening.** It is on by default. The first download takes up to a few minutes (OFAC's
storage is slow); after that it loads from the cache in about a second.
   - `GET /api/v1/admin/compliance/sanctions/status` shows `loaded: true` and the counts.
   - `GET /api/v1/admin/compliance/sanctions/screen?name=Osama bin Laden` hits `BIN LADIN, Usama bin Muhammad bin Awad`, DOB 30 Jul 1957.
   - Send a remittance from "Viktor Bout" or "Osama bin Laden": it is **PENDING_REVIEW**, and `screeningNotes` quotes the OFAC entry.
   - Common names also match real entries, for example "Musa Ibrahim" or "Ahmed Hassan". That is why a hit is a review, not a block: the analyst compares the DOB and clears false positives in seconds.

### Production trade-offs (by design)

| Decision | What we chose | What we give up | What makes it safe |
|----------|---------------|-----------------|--------------------|
| Partner funding | **Prefunded only** | Partners prefer credit lines | We never lend to a partner by accident |
| NGN liquidity | Pay recipients from NGN we already hold; sell USD **once a day** | Capital tied up, and an overnight FX position | Spread, FX position endpoint, unrealised P&L shown live |
| Quote lock | 10 minutes, one use, priced per remittance | Partners would like a day-long rate | A short window caps market moves against us |
| Rate source | Mid rate minus a configured spread, with a **deviation guard**: a new mid rate more than 10% away is refused unless forced | A real crash needs a second confirmation | A typo cannot become a live rate |
| Held remittances | Rate locked at acceptance | We carry FX risk while it waits for review | Review is quick; the position shows up in the FX numbers |
| Failed payout refund | Partner gets the **full USD** amount and fee | We absorb FX movement on failures | Recipient checks run before charging, so failures are rare |
| Screening hits | **Hold** for review, not auto-reject | Needs an analyst queue | Fewer false rejections; every decision audited |
| Sanctions matching | The real OFAC SDN list with our own matcher, tuned to over-match | Common names cause false positives for analysts to clear | Hits carry the DOB; the next step is to send the sender's DOB with each remittance and auto-clear on a DOB mismatch |
| Sanctions list outage | **Fail closed**: no list, every remittance is held | Slower payouts during an outage | Never pays out unscreened; the cached copy makes this rare |
| FX deal | Sell the **whole** open position at once | No partial sales | Gain/loss is exact, and both positions return to zero |
| Rounding | Rates kept to 8 decimals; NGN rounded **down** to the kobo | Recipient loses at most 1 kobo | Deterministic and auditable |
| Architecture | Modular monolith; modules talk only through `api/` packages | No independent scaling | Clean seams to split later |

### Cut for the deadline (and what they would take)

| Not built | Why it was cut | What it would take |
|-----------|----------------|--------------------|
| Remittance payout to **bank accounts** | Wallet payout shows the whole flow; the recipient can already withdraw with the existing bank transfer | Let `PayoutProcessor` take a GL account as the source instead of a customer wallet |
| Partner auth by **API key + HMAC signature** | Partners use the same JWT login with role `PARTNER`, which already works on Swagger | An API-key table and a request-signing filter |
| **Webhooks to partners** | Partners poll `GET /partner/remittances/{ref}` | A signed outbound webhook with retries (outbox table) |
| Daily caps per partner, low-balance alerts | Per-remittance min/max and the prefund check cover the demo | A usage query, as transfers already do |
| Paystack **statement import**, bank statement matching, `MISSING_IN_LEDGER` breaks | Reconciliation asks the provider about each of our payouts (catches lost webhooks and missing payouts) | A file/API import into a staging table plus a matcher |
| Paystack top-up instructions and collection settlement (T+1); the live balance check exists | Treasury operations rather than product | Post Paystack settlements against `GL-NGN-PAYSTACK-COLLECTIONS` |
| NFIU currency transaction report extraction | Reporting, not money movement | A query over transfers and remittances ≥ ₦5m |
| GBP/EUR corridors, USD customer wallets | One corridor proves the design | Currency-parameterised GL accounts and quotes |
| ShedLock | The demo runs as one instance | One dependency and an annotation per job |

### Demo on Swagger (about 10 minutes)

Run with `PAYBRIDGE_PAYMENTS_PROVIDER=simulated` against an empty database, and log in as the bootstrap
admin. Use `.local` email addresses so no real emails go out.

1. **Setup (admin).**
   - `POST /api/v1/auth/register` a customer, for example "Ada Obi". Note the wallet number.
   - Raise Ada to tier 2: as Ada, `POST /api/v1/kyc/bvn` (any BVN in simulated mode; `22222222222` with Dojah, see above), or as admin `PUT /api/v1/admin/customers/{id}/kyc-tier`. Tier 1 wallets cap at ₦300,000, and two $100 remittances exceed that.
   - `POST /api/v1/admin/partners` creates a partner, for example "Diaspora Remit Inc.". Note its `PARTNER-USD-…` account.
2. **Partner wire lands (admin).** `POST /api/v1/admin/settlement/prefunds` with
   `{ "partnerAccountNumber": "PARTNER-USD-…", "amount": 1000.00, "bankReference": "FED-WIRE-001" }`.
   Sending it again changes nothing.
3. **Rate card (anyone).** `GET /api/v1/fx/rates` shows today's live mid rate (`source: OPEN_ER_API`), the customer rate 1.5% below it, the $3 fee, and 600s validity.
4. **Partner sends money.** Log in as the partner, then:
   - `POST /api/v1/partner/quotes` with `{ "amountUsd": 100.00 }`.
   - `POST /api/v1/partner/remittances` with that `quoteReference`, sender "Grace Okafor" / `US`, recipient "Ada Obi" and Ada's wallet number, and any `Idempotency-Key`. The result is **COMPLETED**.
   - Log in as Ada: `GET /api/v1/accounts` shows the quote's `targetAmount`.
5. **Safety checks.**
   - Resend step 4 with the same key: same result, charged once.
   - Reuse the quote with a new key: **QUOTE_USED**.
   - Quote $2,000 with only ~$897 left: **INSUFFICIENT_PREFUND**.
6. **Compliance review.**
   - New quote, sender "Viktor Sanctionov": the result is **PENDING_REVIEW** with the reason.
   - As admin, `GET /api/v1/admin/remittances?status=PENDING_REVIEW`, then `POST /{ref}/approve` (or `/reject` to refund the partner).
   - With sender country `KP`: **REJECTED**, nothing charged.
7. **FX and P&L (admin).**
   - `GET /api/v1/admin/settlement/fx-position` shows the open USD and the unrealised gain.
   - `POST /fx-deals` with `executedRate` set to the `currentMidRate` from fx-position and `"bankReference": "DEAL-001"`. The realised gain is the spread. Both positions return to zero.
   - Try `PUT /api/v1/admin/fx/rates` with a mid rate 50% above the current one: **RATE_DEVIATION** (409).
   - `GET /api/v1/admin/settlement/liquidity` shows the live Paystack test balance against payouts in flight.
8. **Reconciliation (admin + customer).**
   - As Ada, send ₦5,000 to a bank account (`POST /api/v1/transfers/external`, account `0123456789`, bank `058`).
   - As admin, `POST /api/v1/admin/simulations/transfers/{ref}/webhook` with `{ "event": "transfer.success" }`.
   - Then the same endpoint with `{ "event": "transfer.reversed", "sendWebhook": false }`: the bank returned the money, but the webhook was "lost".
   - `POST /api/v1/admin/settlement/reconciliations/payouts` gives a **STATUS_MISMATCH** break (ledger SUCCESS, provider REVERSED).
   - `POST /breaks/{id}/resolve` with `ACCEPT_PROVIDER`: Ada is refunded, and the break is RESOLVED.
9. **Books close.**
   - `GET /api/v1/admin/settlement/integrity-check`: all green.
   - `GET /trial-balance`: NGN and USD each total 0.00.
