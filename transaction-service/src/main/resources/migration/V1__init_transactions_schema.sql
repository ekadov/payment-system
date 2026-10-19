CREATE SCHEMA IF NOT EXISTS transaction;
SET search_path TO transaction;

CREATE TABLE wallet_types
(
    uid           UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    created_at    TIMESTAMP   NOT NULL DEFAULT now(),
    modified_at   TIMESTAMP,
    name          VARCHAR(32) NOT NULL,
    currency_code VARCHAR(3)  NOT NULL,
    status        VARCHAR(18) NOT NULL,
    archived_at   TIMESTAMP,
    user_type     VARCHAR(15),
    creator       VARCHAR(255),
    modifier      VARCHAR(255)
);
CREATE TABLE wallets
(
    uid             UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    created_at      TIMESTAMP   NOT NULL DEFAULT now(),
    modified_at     TIMESTAMP,
    name            VARCHAR(32) NOT NULL,
    wallet_type_uid UUID        NOT NULL REFERENCES wallet_types (uid),
    user_uid        UUID        NOT NULL,
    status          VARCHAR(30) NOT NULL,
    balance         DECIMAL(15, 2)     NOT NULL DEFAULT 0.00,
    archived_at     TIMESTAMP,
    CONSTRAINT account_balance_non_negative CHECK (balance >= 0)
);
CREATE TYPE payment_type AS ENUM ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER');
CREATE TABLE transactions
(
    uid               UUID PRIMARY KEY      DEFAULT gen_random_uuid(),
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    modified_at       TIMESTAMP,
    user_uid          UUID         NOT NULL,
    wallet_uid        UUID         NOT NULL REFERENCES wallets (uid),
    amount            DECIMAL(15, 2)      NOT NULL DEFAULT 0.0,
    type              payment_type NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    comment           VARCHAR(256),
    fee               DECIMAL(15, 2),
    target_wallet_uid UUID,   -- для transfer
    payment_method_id BIGINT, -- для deposit/withdrawal
    failure_reason    VARCHAR(256)
);

-- Кошельки шардируются по user_uid, поэтому уникальность имени обеспечивается в пределах шарда.
CREATE UNIQUE INDEX uk_wallets_user_name ON wallets (user_uid, name);
CREATE INDEX idx_transactions_user_created ON transactions (user_uid, created_at DESC);

INSERT INTO wallet_types (uid, name, currency_code, status, user_type, creator)
VALUES ('00000000-0000-0000-0000-000000000001', 'USD wallet', 'USD', 'ACTIVE', 'INDIVIDUAL', 'system'),
       ('00000000-0000-0000-0000-000000000002', 'EUR wallet', 'EUR', 'ACTIVE', 'INDIVIDUAL', 'system')
ON CONFLICT (uid) DO NOTHING;
