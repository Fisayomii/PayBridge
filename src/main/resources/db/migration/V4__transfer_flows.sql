-- =========================================================================
-- V4__transfer_flows.sql
-- Separates internal (wallet-to-wallet) transfers from external bank payouts,
-- adds PayBridge-owned GL accounts for holding payout funds, and scopes
-- idempotency keys to the sending wallet.
-- =========================================================================

-- 1. ACCOUNTS: distinguish customer wallets from internal GL accounts.
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS account_type VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER';

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_type CHECK (account_type IN ('CUSTOMER', 'INTERNAL'));

INSERT INTO accounts (id, wallet_number, owner_name, email, external_bank_account_number,
                      external_bank_code, balance, currency, created_at, version, account_type)
VALUES (gen_random_uuid(), 'GL-NGN-PAYOUT-CLEARING', 'PayBridge NGN payout clearing',
        'ops@paybridge.local', 'N/A', 'N/A', 0.00, 'NGN', now(), 0, 'INTERNAL'),
       (gen_random_uuid(), 'GL-NGN-PAYSTACK-SETTLEMENT', 'PayBridge NGN Paystack settlement',
        'ops@paybridge.local', 'N/A', 'N/A', 0.00, 'NGN', now(), 0, 'INTERNAL')
ON CONFLICT (wallet_number) DO NOTHING;

-- 2. TRANSFERS: type, external beneficiary, lifecycle timestamps, optimistic lock.
ALTER TABLE transfers
    ADD COLUMN IF NOT EXISTS transfer_type VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64),
    ADD COLUMN IF NOT EXISTS beneficiary_account_number VARCHAR(20),
    ADD COLUMN IF NOT EXISTS beneficiary_bank_code VARCHAR(10),
    ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Existing rows were all wallet-to-wallet transfers.
UPDATE transfers
SET updated_at   = created_at,
    completed_at = CASE WHEN status IN ('SUCCESS', 'FAILED', 'REVERSED') THEN created_at END
WHERE updated_at IS NULL;

ALTER TABLE transfers
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN transfer_type DROP DEFAULT,
    ALTER COLUMN destination_account_id DROP NOT NULL,
    ALTER COLUMN destination_wallet_number DROP NOT NULL,
    ALTER COLUMN recipient_code DROP NOT NULL,
    ALTER COLUMN gateway_transfer_code DROP NOT NULL;

ALTER TABLE transfers
    ADD CONSTRAINT chk_transfers_type CHECK (transfer_type IN ('INTERNAL', 'EXTERNAL')),
    ADD CONSTRAINT chk_transfers_destination CHECK (
        (transfer_type = 'INTERNAL' AND destination_account_id IS NOT NULL)
        OR (transfer_type = 'EXTERNAL' AND beneficiary_account_number IS NOT NULL
            AND beneficiary_bank_code IS NOT NULL AND recipient_code IS NOT NULL)
    );

-- 3. Idempotency keys are unique per sending wallet, not globally
--    (otherwise one customer's key could block, or reveal, another customer's transfer).
ALTER TABLE transfers DROP CONSTRAINT IF EXISTS transfers_idempotency_key_key;
DROP INDEX IF EXISTS idx_transfers_idempotency_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_transfers_source_idempotency
    ON transfers (source_account_id, idempotency_key);

-- 4. Reconciler scans open payouts; history queries go by sender.
CREATE INDEX IF NOT EXISTS idx_transfers_open_payouts
    ON transfers (created_at) WHERE status IN ('PENDING', 'PROCESSING');
CREATE INDEX IF NOT EXISTS idx_transfers_source_account
    ON transfers (source_account_id, created_at DESC);
