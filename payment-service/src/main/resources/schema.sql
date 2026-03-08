-- Payment Service Database Schema

-- Payment table
CREATE TABLE IF NOT EXISTS payment (
    id BIGSERIAL PRIMARY KEY,
    payment_id VARCHAR(50) NOT NULL UNIQUE,
    user_id VARCHAR(100) NOT NULL,
    account_id BIGINT NOT NULL,
    total_amount DECIMAL(19,2) NOT NULL,
    approved_amount DECIMAL(19,2) NOT NULL,
    cancelled_amount DECIMAL(19,2) NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    merchant_id VARCHAR(100),
    order_id VARCHAR(100),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_user_id ON payment(user_id);
CREATE INDEX IF NOT EXISTS idx_payment_account_id ON payment(account_id);
CREATE INDEX IF NOT EXISTS idx_payment_status ON payment(status);
CREATE INDEX IF NOT EXISTS idx_payment_created_at ON payment(created_at);
CREATE INDEX IF NOT EXISTS idx_payment_idempotency_key ON payment(idempotency_key);

-- Payment Transaction table
CREATE TABLE IF NOT EXISTS payment_transaction (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payment(id),
    transaction_id VARCHAR(50) NOT NULL UNIQUE,
    transaction_type VARCHAR(20) NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    bank_transaction_id VARCHAR(50),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    reason VARCHAR(500),
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_payment_txn_payment_id ON payment_transaction(payment_id);
CREATE INDEX IF NOT EXISTS idx_payment_txn_bank_txn_id ON payment_transaction(bank_transaction_id);
CREATE INDEX IF NOT EXISTS idx_payment_txn_created_at ON payment_transaction(created_at);
CREATE INDEX IF NOT EXISTS idx_payment_txn_status_date ON payment_transaction(status, created_at);
