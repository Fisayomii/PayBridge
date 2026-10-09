-- =========================================================================
-- V2__add_fintech_constraints.sql
-- Production Fintech Database Guardrails & Optimistic Locking
-- =========================================================================

-- 1. Prevent negative wallet balances at the PostgreSQL engine level
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_non_negative_balance CHECK (balance >= 0.00);

-- 2. Add optimistic locking version column to prevent double-spend race conditions
ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- 3. Enforce positive transfer amounts and prevent self-transfers at DB level
ALTER TABLE transfers
    ADD CONSTRAINT chk_transfers_positive_amount CHECK (amount > 0.00),
    ADD CONSTRAINT chk_transfers_distinct_wallets CHECK (source_account_id <> destination_account_id);

-- 4. Enforce positive ledger amounts and valid entry types
ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_ledger_positive_amount CHECK (amount > 0.00),
    ADD CONSTRAINT chk_ledger_valid_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT'));
