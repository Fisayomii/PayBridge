-- =========================================================================
-- V6__remittance_settlement.sql
-- Inbound USD -> NGN remittance through prefunded partners, FX quotes,
-- and the settlement module (prefunds, FX deals, reconciliation breaks).
-- =========================================================================

-- 1. PARTNERS are logins with role PARTNER that own one USD prefund account (account_type PARTNER).
ALTER TABLE customers DROP CONSTRAINT IF EXISTS customers_role_check;
ALTER TABLE customers
    ADD CONSTRAINT customers_role_check CHECK (role IN ('CUSTOMER', 'ADMIN', 'PARTNER'));

ALTER TABLE accounts DROP CONSTRAINT IF EXISTS chk_accounts_type;
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_type CHECK (account_type IN ('CUSTOMER', 'INTERNAL', 'PARTNER'));
-- chk_accounts_non_negative_balance (account_type = 'INTERNAL' OR balance >= 0) and
-- chk_accounts_customer_owner already cover PARTNER: a partner can never be overdrawn and always has an owner.

-- 2. GL accounts for the remittance corridor. Each currency balances on its own.
INSERT INTO accounts (id, wallet_number, owner_name, email, balance, currency, created_at, version, account_type, status)
VALUES (gen_random_uuid(), 'GL-USD-NOSTRO', 'PayBridge USD bank account',
        'ops@paybridge.local', 0.00, 'USD', now(), 0, 'INTERNAL', 'ACTIVE'),
       (gen_random_uuid(), 'GL-USD-REMITTANCE-CLEARING', 'PayBridge USD remittances awaiting payout',
        'ops@paybridge.local', 0.00, 'USD', now(), 0, 'INTERNAL', 'ACTIVE'),
       (gen_random_uuid(), 'GL-USD-FX-POSITION', 'PayBridge USD bought from partners, not yet sold',
        'ops@paybridge.local', 0.00, 'USD', now(), 0, 'INTERNAL', 'ACTIVE'),
       (gen_random_uuid(), 'GL-USD-REMITTANCE-FEE-INCOME', 'PayBridge USD remittance fees',
        'ops@paybridge.local', 0.00, 'USD', now(), 0, 'INTERNAL', 'ACTIVE'),
       (gen_random_uuid(), 'GL-NGN-FX-POSITION', 'PayBridge NGN paid out for remittances, not yet bought',
        'ops@paybridge.local', 0.00, 'NGN', now(), 0, 'INTERNAL', 'ACTIVE'),
       (gen_random_uuid(), 'GL-NGN-NOSTRO', 'PayBridge NGN bank account',
        'ops@paybridge.local', 0.00, 'NGN', now(), 0, 'INTERNAL', 'ACTIVE'),
       (gen_random_uuid(), 'GL-NGN-FX-PNL', 'PayBridge realised FX gain/loss',
        'ops@paybridge.local', 0.00, 'NGN', now(), 0, 'INTERNAL', 'ACTIVE')
ON CONFLICT (wallet_number) DO NOTHING;

-- 3. FX QUOTES (fx module): a price honoured until expires_at, usable by exactly one remittance.
CREATE TABLE fx_quotes (
    id              UUID PRIMARY KEY,
    reference       VARCHAR(64)    NOT NULL UNIQUE,
    partner_id      UUID           NOT NULL REFERENCES customers (id),
    source_currency VARCHAR(3)     NOT NULL,
    target_currency VARCHAR(3)     NOT NULL,
    source_amount   NUMERIC(19, 2) NOT NULL CHECK (source_amount > 0.00),
    fee             NUMERIC(19, 2) NOT NULL CHECK (fee >= 0.00),
    mid_rate        NUMERIC(19, 8) NOT NULL CHECK (mid_rate > 0),
    rate            NUMERIC(19, 8) NOT NULL CHECK (rate > 0),
    target_amount   NUMERIC(19, 2) NOT NULL CHECK (target_amount > 0.00),
    expires_at      TIMESTAMPTZ    NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_fx_quotes_partner ON fx_quotes (partner_id, created_at DESC);

-- Audit trail of the reference mid-rate (the simulated rate feed; an admin moves it in the demo).
CREATE TABLE fx_rates (
    id           UUID PRIMARY KEY,
    pair         VARCHAR(7)     NOT NULL,
    mid_rate     NUMERIC(19, 8) NOT NULL CHECK (mid_rate > 0),
    source       VARCHAR(50)    NOT NULL,
    set_by       UUID,
    created_at   TIMESTAMPTZ    NOT NULL
);
CREATE INDEX idx_fx_rates_pair ON fx_rates (pair, created_at DESC);

-- 4. REMITTANCES (remittance module)
CREATE TABLE remittances (
    id                       UUID PRIMARY KEY,
    reference                VARCHAR(64)    NOT NULL UNIQUE,
    partner_id               UUID           NOT NULL REFERENCES customers (id),
    partner_account_id       UUID           NOT NULL REFERENCES accounts (id),
    quote_id                 UUID           NOT NULL UNIQUE REFERENCES fx_quotes (id),
    idempotency_key          VARCHAR(255)   NOT NULL,
    request_fingerprint      VARCHAR(64)    NOT NULL,
    partner_reference        VARCHAR(100),
    -- Travel-rule data (FATF Recommendation 16)
    sender_name              VARCHAR(255)   NOT NULL,
    sender_country           VARCHAR(2)     NOT NULL,
    sender_id_number         VARCHAR(100)   NOT NULL,
    sender_address           VARCHAR(500)   NOT NULL,
    recipient_name           VARCHAR(255)   NOT NULL,
    recipient_wallet_number  VARCHAR(50)    NOT NULL,
    recipient_account_id     UUID           NOT NULL REFERENCES accounts (id),
    purpose                  VARCHAR(100)   NOT NULL,
    -- Money
    source_amount            NUMERIC(19, 2) NOT NULL CHECK (source_amount > 0.00),
    source_currency          VARCHAR(3)     NOT NULL,
    fee                      NUMERIC(19, 2) NOT NULL CHECK (fee >= 0.00),
    rate                     NUMERIC(19, 8) NOT NULL,
    target_amount            NUMERIC(19, 2) NOT NULL CHECK (target_amount > 0.00),
    target_currency          VARCHAR(3)     NOT NULL,
    -- State
    status                   VARCHAR(20)    NOT NULL
        CHECK (status IN ('PENDING_REVIEW', 'COMPLETED', 'REJECTED', 'FAILED')),
    screening_notes          VARCHAR(1000),
    review_note              VARCHAR(500),
    reviewed_by              UUID,
    failure_reason           VARCHAR(500),
    created_at               TIMESTAMPTZ    NOT NULL,
    updated_at               TIMESTAMPTZ    NOT NULL,
    completed_at             TIMESTAMPTZ,
    version                  BIGINT         NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_remittances_partner_idempotency ON remittances (partner_id, idempotency_key);
CREATE INDEX idx_remittances_partner ON remittances (partner_id, created_at DESC);
CREATE INDEX idx_remittances_review ON remittances (created_at) WHERE status = 'PENDING_REVIEW';

-- 5. SETTLEMENT: partner prefunds seen at our USD bank account.
CREATE TABLE partner_prefunds (
    id                 UUID PRIMARY KEY,
    reference          VARCHAR(64)    NOT NULL UNIQUE,
    partner_account_id UUID           NOT NULL REFERENCES accounts (id),
    amount             NUMERIC(19, 2) NOT NULL CHECK (amount > 0.00),
    currency           VARCHAR(3)     NOT NULL,
    -- The bank's own reference for the incoming wire: the same wire can never be booked twice.
    bank_reference     VARCHAR(100)   NOT NULL UNIQUE,
    recorded_by        UUID           NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL
);

-- Daily conversion of the open USD position at the dealer bank.
CREATE TABLE fx_deals (
    id             UUID PRIMARY KEY,
    reference      VARCHAR(64)    NOT NULL UNIQUE,
    bank_reference VARCHAR(100)   NOT NULL UNIQUE,
    usd_amount     NUMERIC(19, 2) NOT NULL CHECK (usd_amount > 0.00),
    executed_rate  NUMERIC(19, 8) NOT NULL CHECK (executed_rate > 0),
    ngn_amount     NUMERIC(19, 2) NOT NULL CHECK (ngn_amount > 0.00),
    ngn_paid_out   NUMERIC(19, 2) NOT NULL,
    realised_pnl   NUMERIC(19, 2) NOT NULL,
    booked_by      UUID           NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL
);

-- Reconciliation runs and the mismatches ("breaks") they find.
CREATE TABLE reconciliation_runs (
    id             UUID PRIMARY KEY,
    run_type       VARCHAR(30)  NOT NULL,
    business_date  DATE         NOT NULL,
    items_checked  INT          NOT NULL,
    breaks_found   INT          NOT NULL,
    triggered_by   UUID,
    started_at     TIMESTAMPTZ  NOT NULL,
    finished_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE reconciliation_breaks (
    id               UUID PRIMARY KEY,
    run_id           UUID          NOT NULL REFERENCES reconciliation_runs (id),
    break_type       VARCHAR(30)   NOT NULL,
    reference        VARCHAR(64)   NOT NULL,
    amount           NUMERIC(19, 2),
    ledger_status    VARCHAR(20),
    provider_status  VARCHAR(20),
    details          VARCHAR(500),
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    resolution       VARCHAR(30),
    resolution_note  VARCHAR(500),
    resolved_by      UUID,
    resolved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_reconciliation_breaks_open ON reconciliation_breaks (created_at) WHERE status = 'OPEN';
-- One open break per reference: re-running reconciliation does not duplicate known breaks.
CREATE UNIQUE INDEX uq_reconciliation_breaks_open_ref ON reconciliation_breaks (reference) WHERE status = 'OPEN';
