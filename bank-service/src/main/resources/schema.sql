-- Bank Service Database Schema

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(100) NOT NULL UNIQUE,
    phone VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_user_email ON users(email);

-- Account table
CREATE TABLE IF NOT EXISTS account (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    account_number VARCHAR(20) NOT NULL UNIQUE,
    account_type VARCHAR(20) NOT NULL,
    balance DECIMAL(19,2) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    currency VARCHAR(3) NOT NULL DEFAULT 'KRW',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_account_user_id ON account(user_id);
CREATE INDEX IF NOT EXISTS idx_account_account_number ON account(account_number);
CREATE INDEX IF NOT EXISTS idx_account_status ON account(status);

-- Transaction table
CREATE TABLE IF NOT EXISTS transaction (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES account(id),
    transaction_id VARCHAR(50) NOT NULL UNIQUE,
    transaction_type VARCHAR(20) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    balance_after DECIMAL(19,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    reference_type VARCHAR(50),
    reference_id VARCHAR(100),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_transaction_account_id ON transaction(account_id);
CREATE INDEX IF NOT EXISTS idx_transaction_created_at ON transaction(created_at);
CREATE INDEX IF NOT EXISTS idx_transaction_reference ON transaction(reference_type, reference_id);
CREATE INDEX IF NOT EXISTS idx_transaction_account_date ON transaction(account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transaction_idempotency_key ON transaction(idempotency_key);

-- Ledger table
CREATE TABLE IF NOT EXISTS ledger (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES account(id),
    transaction_id VARCHAR(50) NOT NULL,
    entry_type VARCHAR(20) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    balance_before DECIMAL(19,2) NOT NULL,
    balance_after DECIMAL(19,2) NOT NULL,
    reference_type VARCHAR(50),
    reference_id VARCHAR(100),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    memo VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ledger_account_id ON ledger(account_id);
CREATE INDEX IF NOT EXISTS idx_ledger_created_at ON ledger(created_at);
CREATE INDEX IF NOT EXISTS idx_ledger_reference ON ledger(reference_type, reference_id);
CREATE INDEX IF NOT EXISTS idx_ledger_account_date ON ledger(account_id, created_at DESC);

-- Sample data
INSERT INTO users (username, email, phone, status) VALUES
('testuser1', 'test1@example.com', '010-1234-5678', 'ACTIVE'),
('testuser2', 'test2@example.com', '010-2345-6789', 'ACTIVE')
ON CONFLICT DO NOTHING;
