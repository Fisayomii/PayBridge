-- =========================================================================
-- V5__customers_funding_limits.sql
-- Persistent customers + refresh tokens, wallet ownership and status (freeze),
-- ledgered wallet funding, transfer fees, and saved beneficiaries.
-- =========================================================================

-- 1. CUSTOMERS (customer module)
CREATE TABLE customers (
    id                    UUID PRIMARY KEY,
    email                 VARCHAR(255) NOT NULL,
    full_name             VARCHAR(255) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    role                  VARCHAR(20)  NOT NULL CHECK (role IN ('CUSTOMER', 'ADMIN')),
    status                VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    kyc_tier              SMALLINT     NOT NULL DEFAULT 1 CHECK (kyc_tier BETWEEN 1 AND 3),
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_customers_email ON customers (lower(email));

-- Opaque refresh tokens, stored hashed. A family is one login session; reuse of a rotated
-- token revokes the whole family (stolen-token detection).
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY,
    customer_id UUID         NOT NULL REFERENCES customers (id),
    family_id   UUID         NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_customer ON refresh_tokens (customer_id);

-- 2. ACCOUNTS: owner, status, and relaxed legacy columns.
ALTER TABLE accounts
    ADD COLUMN customer_id   UUID REFERENCES customers (id),
    ADD COLUMN status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ALTER COLUMN external_bank_account_number DROP NOT NULL,
    ALTER COLUMN external_bank_code DROP NOT NULL;

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_status CHECK (status IN ('ACTIVE', 'POST_NO_DEBIT', 'FROZEN'));

-- Every new customer wallet must have an owner. NOT VALID: pre-existing demo wallets
-- (seeded before customers existed) are left ownerless and unusable rather than guessed at.
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_customer_owner
        CHECK (account_type = 'INTERNAL' OR customer_id IS NOT NULL) NOT VALID;

-- One wallet per currency per customer.
CREATE UNIQUE INDEX uq_accounts_customer_currency ON accounts (customer_id, currency) WHERE customer_id IS NOT NULL;

-- GL accounts are double-entry counterparts and may legitimately go negative
-- (e.g. collections/adjustments are debited when customers are credited). Customer wallets never may.
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS chk_accounts_non_negative_balance;
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_non_negative_balance CHECK (account_type = 'INTERNAL' OR balance >= 0.00);

INSERT INTO accounts (id, wallet_number, owner_name, email, balance, currency, created_at, version, account_type, status)
VALUES (gen_random_uuid(), 'GL-NGN-PAYSTACK-COLLECTIONS', 'PayBridge NGN Paystack collections',
        'ops@paybridge.local', 0.00, 'NGN', now(), 0, 'INTERNAL', 'ACTIVE'),
       (gen_random_uuid(), 'GL-NGN-FEE-INCOME', 'PayBridge NGN fee income',
        'ops@paybridge.local', 0.00, 'NGN', now(), 0, 'INTERNAL', 'ACTIVE'),
       (gen_random_uuid(), 'GL-NGN-ADJUSTMENTS', 'PayBridge NGN manual adjustments',
        'ops@paybridge.local', 0.00, 'NGN', now(), 0, 'INTERNAL', 'ACTIVE')
ON CONFLICT (wallet_number) DO NOTHING;

-- Audit trail for freezes / post-no-debit.
CREATE TABLE account_status_history (
    id         UUID PRIMARY KEY,
    account_id UUID         NOT NULL REFERENCES accounts (id),
    old_status VARCHAR(20)  NOT NULL,
    new_status VARCHAR(20)  NOT NULL,
    reason     VARCHAR(500) NOT NULL,
    changed_by UUID         NOT NULL,
    changed_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_account_status_history_account ON account_status_history (account_id, changed_at DESC);

-- 3. FUNDINGS (funding module): every naira entering or administratively leaving a wallet.
CREATE TABLE fundings (
    id                 UUID PRIMARY KEY,
    reference          VARCHAR(64)   NOT NULL UNIQUE,
    account_id         UUID          NOT NULL REFERENCES accounts (id),
    channel            VARCHAR(30)   NOT NULL CHECK (channel IN ('PAYSTACK_CHECKOUT', 'ADMIN_ADJUSTMENT')),
    direction          VARCHAR(10)   NOT NULL CHECK (direction IN ('CREDIT', 'DEBIT')),
    amount             NUMERIC(19, 2) NOT NULL CHECK (amount > 0.00),
    currency           VARCHAR(3)    NOT NULL,
    status             VARCHAR(20)   NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED', 'ABANDONED')),
    authorization_url  VARCHAR(500),
    provider_reference VARCHAR(255),
    reason             VARCHAR(500),
    initiated_by       UUID          NOT NULL,
    idempotency_key    VARCHAR(255)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    completed_at       TIMESTAMPTZ,
    version            BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_fundings_initiator_idempotency ON fundings (initiated_by, idempotency_key);
CREATE INDEX idx_fundings_account ON fundings (account_id, created_at DESC);
CREATE INDEX idx_fundings_pending ON fundings (created_at) WHERE status = 'PENDING';

-- 4. TRANSFERS: fee, initiator, beneficiary link.
ALTER TABLE transfers
    ADD COLUMN fee            NUMERIC(19, 2) NOT NULL DEFAULT 0.00,
    ADD COLUMN initiated_by   UUID,
    ADD COLUMN beneficiary_id UUID;
ALTER TABLE transfers
    ADD CONSTRAINT chk_transfers_fee_non_negative CHECK (fee >= 0.00);

-- 5. BENEFICIARIES (transfer module)
CREATE TABLE beneficiaries (
    id             UUID PRIMARY KEY,
    customer_id    UUID         NOT NULL REFERENCES customers (id),
    type           VARCHAR(20)  NOT NULL CHECK (type IN ('INTERNAL', 'EXTERNAL')),
    nickname       VARCHAR(100),
    wallet_number  VARCHAR(255),
    account_number VARCHAR(20),
    bank_code      VARCHAR(10),
    resolved_name  VARCHAR(255) NOT NULL,
    recipient_code VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT chk_beneficiaries_destination CHECK (
        (type = 'INTERNAL' AND wallet_number IS NOT NULL)
        OR (type = 'EXTERNAL' AND account_number IS NOT NULL AND bank_code IS NOT NULL AND recipient_code IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uq_beneficiaries_internal ON beneficiaries (customer_id, wallet_number) WHERE type = 'INTERNAL';
CREATE UNIQUE INDEX uq_beneficiaries_external ON beneficiaries (customer_id, account_number, bank_code) WHERE type = 'EXTERNAL';
