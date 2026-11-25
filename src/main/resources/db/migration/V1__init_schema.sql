-- =====================================================================
-- V1: initial schema for the Digital Wallet API
-- Kept in strict sync with the JPA entities (spring.jpa.hibernate.ddl-auto=validate).
-- Do not edit after it has been applied anywhere: add a new V<n>__*.sql instead.
-- =====================================================================

-- ---------------------------------------------------------------------
-- users  ("user" is a reserved word in PostgreSQL)
-- ---------------------------------------------------------------------
CREATE TABLE users
(
    id         BIGSERIAL PRIMARY KEY,
    full_name  VARCHAR(120) NOT NULL,
    email      VARCHAR(150) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- ---------------------------------------------------------------------
-- wallets
-- ---------------------------------------------------------------------
CREATE TABLE wallets
(
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT         NOT NULL,
    currency   VARCHAR(3)     NOT NULL,
    balance    NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    status     VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_wallets_balance_non_negative CHECK (balance >= 0),
    CONSTRAINT chk_wallets_currency CHECK (currency IN ('BGN', 'EUR', 'USD')),
    CONSTRAINT chk_wallets_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

CREATE INDEX idx_wallets_user_id ON wallets (user_id);

-- ---------------------------------------------------------------------
-- categories
-- ---------------------------------------------------------------------
CREATE TABLE categories
(
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    type VARCHAR(20) NOT NULL,
    CONSTRAINT uq_categories_name UNIQUE (name),
    CONSTRAINT chk_categories_type CHECK (type IN ('INCOME', 'EXPENSE', 'TRANSFER'))
);

-- ---------------------------------------------------------------------
-- transactions
-- ---------------------------------------------------------------------
CREATE TABLE transactions
(
    id               BIGSERIAL PRIMARY KEY,
    source_wallet_id BIGINT,                 -- NULL for DEPOSIT
    target_wallet_id BIGINT,                 -- NULL for WITHDRAWAL
    category_id      BIGINT,
    amount           NUMERIC(19, 4) NOT NULL,
    type             VARCHAR(20)    NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    idempotency_key  VARCHAR(100)   NOT NULL,
    description      VARCHAR(255),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_transactions_source_wallet FOREIGN KEY (source_wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_transactions_target_wallet FOREIGN KEY (target_wallet_id) REFERENCES wallets (id),
    CONSTRAINT fk_transactions_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT uq_transactions_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_transactions_type CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    CONSTRAINT chk_transactions_status CHECK (status IN ('COMPLETED', 'FAILED')),
    -- Wallet columns must match the transaction type.
    CONSTRAINT chk_transactions_wallets_by_type CHECK (
        (type = 'DEPOSIT' AND source_wallet_id IS NULL AND target_wallet_id IS NOT NULL)
            OR (type = 'WITHDRAWAL' AND source_wallet_id IS NOT NULL AND target_wallet_id IS NULL)
            OR (type = 'TRANSFER' AND source_wallet_id IS NOT NULL AND target_wallet_id IS NOT NULL
            AND source_wallet_id <> target_wallet_id)
        )
);

-- Composite indexes: the leading column serves lookups by wallet, the second one serves
-- the history query (filter by wallet + order/range by created_at).
CREATE INDEX idx_transactions_source_wallet_created ON transactions (source_wallet_id, created_at);
CREATE INDEX idx_transactions_target_wallet_created ON transactions (target_wallet_id, created_at);
CREATE INDEX idx_transactions_created_at ON transactions (created_at);
CREATE INDEX idx_transactions_category_id ON transactions (category_id);

-- ---------------------------------------------------------------------
-- tags
-- ---------------------------------------------------------------------
CREATE TABLE tags
(
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    CONSTRAINT uq_tags_name UNIQUE (name)
);

-- ---------------------------------------------------------------------
-- transaction_tags  (Many-to-Many join table)
-- ---------------------------------------------------------------------
CREATE TABLE transaction_tags
(
    transaction_id BIGINT NOT NULL,
    tag_id         BIGINT NOT NULL,
    CONSTRAINT pk_transaction_tags PRIMARY KEY (transaction_id, tag_id),
    CONSTRAINT fk_transaction_tags_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions (id) ON DELETE CASCADE,
    CONSTRAINT fk_transaction_tags_tag FOREIGN KEY (tag_id)
        REFERENCES tags (id) ON DELETE CASCADE
);

-- The PK covers lookups by transaction_id; this one covers tag filters and ON DELETE CASCADE from tags.
CREATE INDEX idx_transaction_tags_tag_id ON transaction_tags (tag_id);
