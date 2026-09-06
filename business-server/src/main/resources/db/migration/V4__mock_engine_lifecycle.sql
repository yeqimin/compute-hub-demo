ALTER TABLE mock_engine_command
  ADD COLUMN operation_type VARCHAR(32) NULL AFTER command_id,
  ADD COLUMN tenant_id BIGINT NULL AFTER instance_no,
  ADD COLUMN result_message VARCHAR(500) NULL AFTER callback_url,
  ADD COLUMN executed_at TIMESTAMP(3) NULL AFTER result_message;

UPDATE mock_engine_command
SET operation_type = 'CREATE'
WHERE operation_type IS NULL;

ALTER TABLE mock_engine_command
  MODIFY operation_type VARCHAR(32) NOT NULL;
