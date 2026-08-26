-- Migration V3: Add Unique Idempotency Constraint and Fingerprint Index

ALTER TABLE retry_attempts
    ADD CONSTRAINT uk_retry_attempt_tenant_key UNIQUE (tenant_id, idempotency_key);

CREATE INDEX idx_retry_attempt_fingerprint
    ON retry_attempts (tenant_id, request_fingerprint);
