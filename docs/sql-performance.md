# SQL 与慢查询设计

## 关键索引

| 查询 | 索引 |
|---|---|
| 租户实例按状态倒序分页 | `compute_instance(tenant_id, status, created_at, id)` |
| 租户账务流水倒序分页 | `wallet_ledger(tenant_id, created_at, id)` |
| 可执行任务扫描 | `async_task(state, next_retry_at, id)` |
| Outbox 批量领取 | `outbox_event(state, next_retry_at, id)` |
| 业务流水防重 | `wallet_ledger(tenant_id, biz_no, type)` UNIQUE |
| HTTP 请求防重 | `idempotency_record(actor_id, idempotency_key)` UNIQUE |

## EXPLAIN 验证

启动项目后执行：

```sql
EXPLAIN ANALYZE
SELECT id, instance_no, status, created_at
FROM compute_instance
WHERE tenant_id = 2 AND status = 'RUNNING'
ORDER BY created_at DESC, id DESC
LIMIT 20;

EXPLAIN ANALYZE
SELECT id, event_id, aggregate_id
FROM outbox_event
WHERE state = 'READY' AND next_retry_at <= NOW(3)
ORDER BY id
LIMIT 20 FOR UPDATE SKIP LOCKED;
```

预期实例查询使用 `idx_instance_tenant_status_created`，Outbox 查询使用 `idx_outbox_state_retry`，避免全表扫描和文件排序。Demo 使用页码分页方便后台界面；生产环境数据量大时建议将实例和流水列表改为基于 `(created_at,id)` 的游标分页。

## 并发策略

- 钱包只锁定单个租户行，事务内不调用远程服务。
- 先写 Outbox 再异步调用引擎，缩短资金事务持锁时间。
- 多 Worker 使用 `SKIP LOCKED` 分片领取，避免全局互斥。
- 查询接口一次 JOIN 返回产品、集群和任务摘要，避免 N+1。
