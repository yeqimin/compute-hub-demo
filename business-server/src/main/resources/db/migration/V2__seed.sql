INSERT INTO tenant(id, code, name) VALUES (1, 'PLATFORM', '平台运营中心'), (2, 'ACME-AI', '星云智能科技');
INSERT INTO tenant_wallet(tenant_id, available_cent, frozen_cent) VALUES (1, 10000000, 0), (2, 1000000, 0);

INSERT INTO sys_role(id, code, name) VALUES
  (1, 'PLATFORM_ADMIN', '平台管理员'), (2, 'TENANT_ADMIN', '租户管理员'), (3, 'VIEWER', '只读访客');
INSERT INTO sys_permission(id, code, name) VALUES
  (1, 'tenant:manage', '租户管理'), (2, 'product:manage', '产品管理'),
  (3, 'instance:create', '创建实例'), (4, 'instance:retry', '任务重试'),
  (5, 'billing:recharge', '钱包充值'), (6, 'resource:read', '资源查看');
INSERT INTO sys_role_permission(role_id, permission_id)
SELECT 1, id FROM sys_permission;
INSERT INTO sys_role_permission(role_id, permission_id) VALUES (2,3),(2,4),(2,5),(2,6),(3,6);

INSERT INTO sys_user(id, tenant_id, username, display_name, password_hash) VALUES
  (1, NULL, 'admin', '平台管理员', 'BOOTSTRAP'),
  (2, 2, 'tenant_admin', '星云租户管理员', 'BOOTSTRAP'),
  (3, 2, 'viewer', '只读体验账号', 'BOOTSTRAP');
INSERT INTO sys_user_role(user_id, role_id) VALUES (1,1),(2,2),(3,3);

INSERT INTO compute_product(id, sku, name, gpu_model, gpu_count, cpu_cores, memory_gb, price_cent) VALUES
  (1, 'GPU-A10-1', 'A10 推理型', 'NVIDIA A10', 1, 8, 32, 12800),
  (2, 'GPU-A100-2', 'A100 训练型', 'NVIDIA A100', 2, 32, 128, 56800),
  (3, 'GPU-H800-8', 'H800 大模型型', 'NVIDIA H800', 8, 96, 768, 288000);

INSERT INTO compute_cluster(id, code, name, region, status) VALUES
  (1, 'SH-GPU-01', '上海 GPU 集群', 'cn-shanghai', 'HEALTHY'),
  (2, 'BJ-GPU-01', '北京训练集群', 'cn-beijing', 'HEALTHY');
INSERT INTO compute_node(cluster_id, name, gpu_model, gpu_total, gpu_allocated, cpu_cores, memory_gb, status) VALUES
  (1, 'sh-gpu-node-01', 'NVIDIA A10', 8, 3, 64, 256, 'READY'),
  (1, 'sh-gpu-node-02', 'NVIDIA A100', 8, 5, 128, 512, 'READY'),
  (2, 'bj-gpu-node-01', 'NVIDIA H800', 8, 6, 192, 1536, 'READY'),
  (2, 'bj-gpu-node-02', 'NVIDIA H800', 8, 2, 192, 1536, 'READY');
