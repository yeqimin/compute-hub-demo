ALTER TABLE compute_instance
  ADD COLUMN active_task_id BIGINT NULL,
  ADD COLUMN deleted_at TIMESTAMP(3) NULL,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
  ADD INDEX idx_instance_active_task(active_task_id);

ALTER TABLE async_task
  ADD COLUMN operation_type VARCHAR(32) NULL,
  ADD COLUMN previous_instance_status VARCHAR(32) NULL,
  ADD COLUMN target_instance_status VARCHAR(32) NULL,
  ADD COLUMN scenario VARCHAR(32) NULL,
  ADD COLUMN actor_id BIGINT NULL,
  ADD COLUMN message_id VARCHAR(128) NULL,
  ADD COLUMN engine_event_id VARCHAR(64) NULL,
  ADD COLUMN source_task_id BIGINT NULL,
  ADD COLUMN manual_retry_count INT NOT NULL DEFAULT 0,
  ADD COLUMN accepted_at TIMESTAMP(3) NULL,
  ADD COLUMN finished_at TIMESTAMP(3) NULL,
  ADD INDEX idx_task_tenant_state_operation_created
    (tenant_id, state, operation_type, created_at, id),
  ADD INDEX idx_task_instance_created(instance_id, created_at, id);

ALTER TABLE outbox_event
  ADD COLUMN task_id BIGINT NULL,
  ADD COLUMN command_id VARCHAR(64) NULL,
  ADD COLUMN message_id VARCHAR(128) NULL,
  ADD COLUMN publish_token VARCHAR(64) NULL,
  ADD COLUMN locked_at TIMESTAMP(3) NULL,
  ADD COLUMN published_at TIMESTAMP(3) NULL,
  ADD INDEX idx_outbox_publish_lease(state, locked_at, next_retry_at, id);

CREATE TABLE operation_audit_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NULL,
  actor_id BIGINT NULL,
  instance_id BIGINT NULL,
  task_id BIGINT NULL,
  action VARCHAR(64) NOT NULL,
  before_state VARCHAR(32) NULL,
  after_state VARCHAR(32) NULL,
  result VARCHAR(32) NOT NULL,
  error VARCHAR(500) NULL,
  trace_id VARCHAR(64) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_audit_tenant_created(tenant_id, created_at, id),
  INDEX idx_audit_instance_created(instance_id, created_at, id)
);

CREATE TABLE realtime_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NULL,
  event_type VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(32) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  payload JSON NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_realtime_tenant_id(tenant_id, id),
  INDEX idx_realtime_created(created_at, id)
);

CREATE TABLE dead_letter_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  message_id VARCHAR(128) NOT NULL UNIQUE,
  task_id BIGINT NULL,
  tenant_id BIGINT NULL,
  raw_payload LONGTEXT NOT NULL,
  headers JSON NOT NULL,
  failure_reason VARCHAR(500) NOT NULL,
  state VARCHAR(32) NOT NULL,
  redriven_at TIMESTAMP(3) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

INSERT INTO sys_permission(code, name) VALUES
  ('instance:operate', '实例生命周期操作'),
  ('task:redrive', '死信重新投递');
INSERT INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
WHERE r.code IN ('PLATFORM_ADMIN', 'TENANT_ADMIN') AND p.code = 'instance:operate';
INSERT INTO sys_role_permission(role_id, permission_id)
SELECT r.id, p.id FROM sys_role r JOIN sys_permission p
WHERE r.code = 'PLATFORM_ADMIN' AND p.code = 'task:redrive';

UPDATE async_task t
JOIN compute_instance i ON i.id = t.instance_id
JOIN compute_order o ON o.id = i.order_id
SET t.operation_type = 'CREATE',
    t.previous_instance_status = 'REQUESTED',
    t.target_instance_status = 'RUNNING',
    t.scenario = i.scenario,
    t.actor_id = o.created_by,
    t.state = CASE t.state WHEN 'READY' THEN 'PENDING' WHEN 'SUCCESS' THEN 'SUCCEEDED' ELSE t.state END;

UPDATE outbox_event o
JOIN async_task t ON t.instance_id = o.aggregate_id
  AND JSON_UNQUOTE(JSON_EXTRACT(o.payload, '$.commandId')) = t.command_id
SET o.task_id = t.id, o.command_id = t.command_id,
    o.message_id = o.event_id, t.message_id = o.event_id,
    o.state = CASE o.state WHEN 'WAITING_CALLBACK' THEN 'SENT' ELSE o.state END;

UPDATE compute_instance SET status = 'CREATING' WHERE status = 'DISPATCHING';
UPDATE compute_instance i
JOIN async_task t ON t.instance_id = i.id
SET i.active_task_id = t.id
WHERE i.status IN ('REQUESTED', 'CREATING', 'UNKNOWN');

ALTER TABLE async_task
  MODIFY operation_type VARCHAR(32) NOT NULL,
  MODIFY previous_instance_status VARCHAR(32) NOT NULL,
  MODIFY target_instance_status VARCHAR(32) NOT NULL,
  MODIFY scenario VARCHAR(32) NOT NULL,
  MODIFY actor_id BIGINT NOT NULL,
  MODIFY message_id VARCHAR(128) NOT NULL;
ALTER TABLE outbox_event
  MODIFY task_id BIGINT NOT NULL,
  MODIFY command_id VARCHAR(64) NOT NULL,
  MODIFY message_id VARCHAR(128) NOT NULL;
