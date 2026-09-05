ALTER TABLE idempotency_record
  ADD COLUMN processing_token VARCHAR(64) NULL,
  ADD COLUMN locked_at TIMESTAMP(3) NULL;
