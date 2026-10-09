-- =========================================================================
-- V1__init_schema.sql
-- PayBridge Modular Monolith: Wallets (account), Bookkeeping (ledger), Transfers
-- =========================================================================

-- 1. ACCOUNTS TABLE (account module)
CREATE TABLE IF NOT EXISTS accounts (
    id UUID PRIMARY KEY,
    wallet_number VARCHAR(255) NOT NULL UNIQUE,
    owner_name VARCHAR(255) NOT NULL,
    external_bank_account_number VARCHAR(255) NOT NULL,
    external_bank_code VARCHAR(255) NOT NULL,
    balance NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_accounts_wallet_number ON accounts(wallet_number);

-- 2. LEDGER ENTRIES TABLE (ledger module)
CREATE TABLE IF NOT EXISTS ledger_entries (
    id UUID PRIMARY KEY,
    transaction_reference VARCHAR(255) NOT NULL,
    account_id UUID NOT NULL,
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    narration VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_ledger_tx_reference ON ledger_entries(transaction_reference);
CREATE INDEX IF NOT EXISTS idx_ledger_account_id ON ledger_entries(account_id);

-- 3. TRANSFERS TABLE (transfer module)
CREATE TABLE IF NOT EXISTS transfers (
    id UUID PRIMARY KEY,
    reference VARCHAR(255) NOT NULL UNIQUE,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    source_account_id UUID NOT NULL,
    source_wallet_number VARCHAR(255) NOT NULL,
    destination_account_id UUID NOT NULL,
    destination_wallet_number VARCHAR(255) NOT NULL,
    resolved_beneficiary_name VARCHAR(255) NOT NULL,
    recipient_code VARCHAR(255) NOT NULL,
    gateway_transfer_code VARCHAR(255) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'REVERSED')),
    narration VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
    );

CREATE INDEX IF NOT EXISTS idx_transfers_reference ON transfers(reference);
CREATE INDEX IF NOT EXISTS idx_transfers_idempotency_key ON transfers(idempotency_key);