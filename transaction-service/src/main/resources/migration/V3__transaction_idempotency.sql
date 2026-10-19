SET search_path TO transaction;

ALTER TABLE transactions ADD COLUMN idempotency_key UUID;
ALTER TABLE transactions ADD COLUMN request_hash VARCHAR(64);

CREATE UNIQUE INDEX uk_transactions_user_idempotency ON transactions (user_uid, idempotency_key);
