-- =========================================================================
-- V7__kyc_verifications.sql
-- Self-service BVN verification through an identity provider (Dojah).
-- The raw BVN is never stored: only a masked copy for display and a keyed hash
-- (HMAC-SHA256) to stop one BVN being used to verify several profiles.
-- =========================================================================

CREATE TABLE kyc_verifications (
    id              UUID PRIMARY KEY,
    customer_id     UUID         NOT NULL REFERENCES customers (id),
    id_type         VARCHAR(10)  NOT NULL CHECK (id_type IN ('BVN')),
    id_number_mask  VARCHAR(20)  NOT NULL,
    id_number_hash  VARCHAR(64)  NOT NULL,
    provider        VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('VERIFIED', 'FAILED')),
    failure_reason  VARCHAR(300),
    tier_before     SMALLINT     NOT NULL,
    tier_after      SMALLINT     NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_kyc_verifications_customer ON kyc_verifications (customer_id, created_at DESC);
-- A BVN can verify only one PayBridge profile.
CREATE UNIQUE INDEX uq_kyc_verified_id ON kyc_verifications (id_type, id_number_hash) WHERE status = 'VERIFIED';
