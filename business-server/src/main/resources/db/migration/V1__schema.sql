CREATE TABLE tenant (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NULL,
  username VARCHAR(64) NOT NULL UNIQUE,
  display_name VARCHAR(128) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

CREATE TABLE sys_role (id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(64) NOT NULL UNIQUE, name VARCHAR(128) NOT NULL);
CREATE TABLE sys_permission (id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(128) NOT NULL UNIQUE, name VARCHAR(128) NOT NULL);
CREATE TABLE sys_user_role (user_id BIGINT NOT NULL, role_id BIGINT NOT NULL, PRIMARY KEY(user_id, role_id));
CREATE TABLE sys_role_permission (role_id BIGINT NOT NULL, permission_id BIGINT NOT NULL, PRIMARY KEY(role_id, permission_id));

CREATE TABLE compute_product (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sku VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL,
  gpu_model VARCHAR(64) NOT NULL,
  gpu_count INT NOT NULL,
  cpu_cores INT NOT NULL,
  memory_gb INT NOT NULL,
  price_cent BIGINT NOT NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE compute_cluster (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(128) NOT NULL,
  region VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL
);

CREATE TABLE compute_node (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  cluster_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  gpu_model VARCHAR(64) NOT NULL,
  gpu_total INT NOT NULL,
  gpu_allocated INT NOT NULL DEFAULT 0,
  cpu_cores INT NOT NULL,
  memory_gb INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  INDEX idx_node_cluster_status(cluster_id, status, id),
  CONSTRAINT fk_node_cluster FOREIGN KEY(cluster_id) REFERENCES compute_cluster(id)
);

CREATE TABLE tenant_wallet (
  tenant_id BIGINT PRIMARY KEY,
  available_cent BIGINT NOT NULL DEFAULT 0,
  frozen_cent BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_wallet_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id),
  CONSTRAINT chk_wallet_nonnegative CHECK (available_cent >= 0 AND frozen_cent >= 0)
);

CREATE TABLE compute_order (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(64) NOT NULL UNIQUE,
  tenant_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  product_snapshot JSON NOT NULL,
  quantity INT NOT NULL,
  amount_cent BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_order_tenant_created(tenant_id, created_at, id)
);

CREATE TABLE compute_instance (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  instance_no VARCHAR(64) NOT NULL UNIQUE,
  order_id BIGINT NOT NULL UNIQUE,
  tenant_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  cluster_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  scenario VARCHAR(32) NOT NULL,
  engine_instance_id VARCHAR(128),
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_instance_tenant_status_created(tenant_id, status, created_at, id),
  CONSTRAINT fk_instance_order FOREIGN KEY(order_id) REFERENCES compute_order(id)
);

CREATE TABLE wallet_ledger (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ledger_no VARCHAR(64) NOT NULL UNIQUE,
  tenant_id BIGINT NOT NULL,
  biz_no VARCHAR(64) NOT NULL,
  type VARCHAR(32) NOT NULL,
  delta_available_cent BIGINT NOT NULL,
  delta_frozen_cent BIGINT NOT NULL,
  available_after_cent BIGINT NOT NULL,
  frozen_after_cent BIGINT NOT NULL,
  remark VARCHAR(255),
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_ledger_biz_type(tenant_id, biz_no, type),
  INDEX idx_ledger_tenant_created(tenant_id, created_at, id)
);

CREATE TABLE idempotency_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  resource_id BIGINT,
  response_body JSON,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_idempotency_actor_key(actor_id, idempotency_key)
);

CREATE TABLE async_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_no VARCHAR(64) NOT NULL UNIQUE,
  command_id VARCHAR(64) NOT NULL UNIQUE,
  tenant_id BIGINT NOT NULL,
  instance_id BIGINT NOT NULL,
  state VARCHAR(32) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP(3) NOT NULL,
  deadline_at TIMESTAMP(3),
  last_error VARCHAR(500),
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_task_state_retry(state, next_retry_at, id)
);

CREATE TABLE outbox_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL UNIQUE,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  payload JSON NOT NULL,
  state VARCHAR(32) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP(3) NOT NULL,
  last_error VARCHAR(500),
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_outbox_state_retry(state, next_retry_at, id)
);

CREATE TABLE inbox_event (
  engine_event_id VARCHAR(64) PRIMARY KEY,
  command_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE mock_engine_command (
  command_id VARCHAR(64) PRIMARY KEY,
  instance_no VARCHAR(64) NOT NULL,
  scenario VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  engine_instance_id VARCHAR(128),
  callback_url VARCHAR(500) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);
