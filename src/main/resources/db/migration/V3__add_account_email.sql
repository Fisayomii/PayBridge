ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS email VARCHAR(255) NOT NULL DEFAULT 'alerts@paybridge.local';