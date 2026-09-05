# ComputeHub 实例全生命周期、RabbitMQ 与前端增强设计

- 日期：2026-09-05
- 作者：yeqimin
- 状态：已完成业务设计确认，待实施计划评审

## 1. 背景与目标

ComputeHub 当前已打通创建实例、钱包冻结与结算、MySQL Outbox、gRPC Mock Engine、回调防重和超时人工处置的基础链路。本次增强将其扩展为可用于面试演示的完整算力调度业务链路，重点展示：

- 实例创建、启动、停止、重启、删除的完整生命周期。
- 同一实例的严格串行、接口幂等与批量操作部分成功。
- MySQL Outbox、RabbitMQ 可靠投递、gRPC 引擎调用与 REST 回调组成的最终一致性链路。
- 重复消息、重复回调、通信超时、死信、服务重启和迟到回调的恢复能力。
- 可实际操作的企业级前端和具有演示效果的实时看板。

本次不引入真实 Kubernetes、云厂商 SDK、按分钟计费、网关、注册中心或生产级高可用部署。这些能力保留扩展边界，不进入本轮代码范围。

## 2. 方案选择

本次评估了三种调度链路：

1. 业务服务直接调用 gRPC：改动小，但无法充分展示消息可靠投递与异步解耦。
2. MySQL Outbox → RabbitMQ → 调度消费者 → gRPC Mock Engine：保留已有 gRPC 分层，同时引入可靠消息，是本次采用的方案。
3. RabbitMQ 完全替代 gRPC：消息链路更统一，但会削弱跨层 gRPC 接口适配的展示价值。

方案 2 能同时展示业务事务、可靠消息、异步状态机、跨层协议适配和前端实时反馈，且不需要将当前模块化单体拆分成多个业务微服务。

## 3. 整体架构

```text
前端操作
  → Business Server 业务事务
  → 实例 / 任务 / 审计 / Outbox 同事务落库
  → Outbox Publisher
  → RabbitMQ
  → 调度消费者
  → gRPC Mock Engine
  → HMAC REST 回调
  → 状态更新与创建费用结算
  → SSE 实时推送前端
```

### 3.1 模块职责

- `business-server`：负责权限、租户隔离、实例状态机、幂等、钱包结算、Outbox 发布、RabbitMQ 消费、回调收敛、审计和 SSE。
- `mock-engine`：执行创建、启动、停止、重启和删除命令，持久化命令防重，并模拟成功、失败、重复回调和超时。
- `proto`：定义通用实例操作命令、命令查询和集群指标协议。
- RabbitMQ：提供至少一次投递、延迟重试和死信隔离，不作为业务事实来源。
- MySQL：是实例、任务、账务、幂等、Outbox、Inbox 和审计的最终事实来源。
- Redis：用于幂等快速拦截、热点查询缓存和短期连接凭证，不承担最终业务状态。
- `frontend`：提供实例控制台、异步任务中心、操作审计、完整 CRUD 页面和实时监控看板。

### 3.2 故障恢复原则

- RabbitMQ 不可用时，业务事务仍可提交，Outbox 保持待发布。
- Publisher 恢复后重新扫描待发布及超过租约的发布中记录。
- 业务服务或消费者重启后，依据 MySQL 任务状态继续处理。
- Mock Engine 通过 `command_id` 持久化防重，重启后不重复执行已受理命令。

## 4. 实例与任务状态机

### 4.1 实例状态

```text
创建：REQUESTED → CREATING → RUNNING
停止：RUNNING → STOPPING → STOPPED
启动：STOPPED → STARTING → RUNNING
重启：RUNNING → RESTARTING → RUNNING
删除：RUNNING / STOPPED / FAILED / DELETE_FAILED → DELETING → DELETED
结果不明确：任一执行中状态 → UNKNOWN
删除明确失败：DELETING → DELETE_FAILED
```

`UNKNOWN` 不表示操作失败，只表示业务层无法确认引擎事实。`UNKNOWN` 实例必须先人工对账或等待迟到回调，不允许直接发起其他生命周期操作。

### 4.2 明确失败和超时

- 创建明确失败：实例进入 `FAILED`，订单进入 `FAILED`，冻结金额全部解冻。
- 启动、停止或重启明确失败：恢复到该任务记录的操作前稳定状态。
- 删除明确失败：实例进入 `DELETE_FAILED`，可再次删除。
- 任何操作通信重试耗尽：实例和任务进入 `UNKNOWN`。
- 创建操作处于 `UNKNOWN` 时继续冻结资金；后续成功则实扣，失败则解冻。
- 启动、停止、重启和删除不收费，删除不退还创建费用。

### 4.3 异步任务模型

操作类型：

- `CREATE`
- `START`
- `STOP`
- `RESTART`
- `DELETE`
- `RECONCILE`

任务状态：

- `PENDING`：业务事务已创建，等待 Outbox 发布。
- `PUBLISHED`：RabbitMQ 已确认接收。
- `PROCESSING`：消费者已锁定任务并正在调用引擎。
- `RETRY_WAIT`：暂时故障，正在延迟队列中等待下一次尝试。
- `WAITING_CALLBACK`：引擎已受理，等待结果回调。
- `SUCCEEDED`：操作成功且业务状态已收敛。
- `FAILED`：收到明确失败结果并完成补偿。
- `UNKNOWN`：结果不明确，需要对账或迟到回调。
- `DEAD`：消息不可恢复，已进入死信队列。

每个任务记录命令号、实例、操作类型、操作前状态、目标状态、故障场景、重试次数、错误、RabbitMQ 消息号、回调事件号和操作人。

### 4.4 单实例严格串行

- 生命周期事务使用 `SELECT ... FOR UPDATE` 锁定实例行。
- `compute_instance.active_task_id` 非空时拒绝新的生命周期操作。
- 任务进入明确终态后清空 `active_task_id`；`UNKNOWN` 保留活动任务，直到对账收敛。
- `RECONCILE` 是 `UNKNOWN` 任务的附属查询任务，不替换实例的 `active_task_id`；对账成功收敛原任务后再清空。同一原任务同时只允许一个未完成的对账任务。
- 相同操作人与相同 `Idempotency-Key` 返回首次结果；同 Key 不同请求指纹返回 HTTP 409。
- 不同操作与当前活动任务冲突时返回 HTTP 409，响应包含活动任务编号。

### 4.5 故障场景注入

创建、启动、停止、重启和删除共用一套场景：

- `SUCCESS`
- `FAIL`
- `DUPLICATE_CALLBACK`
- `TIMEOUT`

前端默认使用 `SUCCESS`，只在高级演示选项中展开故障场景。

## 5. 数据模型调整

使用新的 Flyway 迁移增量修改，不回写 `V1__schema.sql`。

### 5.1 `compute_instance`

增加：

- `active_task_id BIGINT NULL`：当前唯一活动任务。
- `deleted_at TIMESTAMP(3) NULL`：逻辑删除时间。
- `version BIGINT NOT NULL DEFAULT 0`：条件更新和并发检查。

默认列表排除 `DELETED`，显式选择“已删除”时才返回。

### 5.2 `async_task`

增加：

- `operation_type`、`previous_instance_status`、`target_instance_status`、`scenario`。
- `actor_id`、`message_id`、`engine_event_id`。
- `source_task_id`：对账任务引用原始任务。
- `manual_retry_count`、`accepted_at`、`finished_at`。

增加租户、操作类型、状态、创建时间的联合索引，以支持任务中心的服务端筛选和分页。

### 5.3 `outbox_event`

增加：

- `task_id`、`command_id`、`message_id`，避免多任务后再仅通过 `aggregate_id` 关联。
- `publish_token`、`locked_at`、`published_at`，支持发布租约和 Publisher Confirm。

Outbox 状态使用 `READY`、`PUBLISHING`、`SENT`、`DONE`、`DEAD`。`PUBLISHING` 超过租约时间的记录可被重新领取。

### 5.4 新表

- `operation_audit_log`：追加式记录操作人、租户、实例、任务、动作、前后状态、结果、错误、traceId 和时间。不提供修改或删除接口。
- `realtime_event`：追加式记录 SSE 事件序号、租户、类型、聚合编号和负载，为 `Last-Event-ID` 断线续传提供依据。演示环境保留 7 天。

### 5.5 `mock_engine_command`

增加 `operation_type`、`tenant_id`、`result_message` 和执行时间。`command_id` 仍为主键，保证引擎命令防重。

## 6. gRPC 与回调协议

### 6.1 gRPC

在现有 `ComputeEngine` 服务中增加通用命令：

```text
ExecuteInstance(InstanceCommand) returns (CommandAccepted)
GetCommandStatus(CommandStatusRequest) returns (CommandStatusReply)
GetClusterMetrics(MetricsRequest) returns (ClusterMetricsReply)
```

`InstanceCommand` 包含 `command_id`、`operation_type`、`instance_no`、`engine_instance_id`、`tenant_id`、集群、故障场景和回调地址。原 `CreateInstance` 在迁移期保留为兼容适配入口，新任务全部走 `ExecuteInstance`。

### 6.2 REST 回调

`POST /api/v1/internal/engine/events` 负载增加操作类型、操作结果、引擎实例状态和错误信息。

- 继续使用时间戳与 HMAC-SHA256 签名。
- `engine_event_id` 作为 Inbox 唯一键。
- 重复回调返回首次处理结果，不重复更改实例、订单或账务。
- 仅 `UNKNOWN` 允许迟到回调继续收敛。已明确成功或失败后收到冲突回调时，只写入审计，不覆盖已确认的结果。

## 7. RabbitMQ 可靠投递

### 7.1 RabbitMQ 拓扑

- 业务交换机：`compute.command.x`，持久化 direct exchange。
- 主队列：`compute.command.q`，持久化 quorum queue。
- 延迟队列：`compute.command.retry.2s.q`、`compute.command.retry.4s.q`、`compute.command.retry.8s.q`，通过 TTL 和死信路由返回主队列，不依赖额外 RabbitMQ 插件。
- 死信交换机与队列：`compute.command.dlx`、`compute.command.dead.q`。

消息持久化，队列与交换机声明为 durable。Docker Compose 增加 RabbitMQ Management，管理界面用于演示队列、消费者、重试和死信。

### 7.2 Outbox Publisher

1. 业务事务中同时写入实例、任务、审计、实时事件和 Outbox。
2. Publisher 使用 `FOR UPDATE SKIP LOCKED` 分批领取 `READY` 记录，写入唯一 `publish_token` 并改为 `PUBLISHING`。
3. 发布时开启 Publisher Confirm 和 Return Callback。
4. Broker 确认后，仅持有同一 `publish_token` 的回调可将记录改为 `SENT`，同时将任务改为 `PUBLISHED`。
5. NACK、不可路由或发布异常将记录恢复为 `READY` 并记录错误。
6. “已发布但未来得及改库”允许再次发布，下游使用 `command_id` 防重。

### 7.3 消费、ACK 与重试

- 消费者使用手动 ACK。
- 消费事务通过条件更新将任务从 `PUBLISHED` 或 `RETRY_WAIT` 改为 `PROCESSING`。已处理的重复消息直接 ACK。
- gRPC 引擎成功受理后，任务进入 `WAITING_CALLBACK`，再 ACK RabbitMQ 消息。
- 暂时性 gRPC 故障使用 2、4、8 秒三级延迟队列重试，所有自动重试复用同一 `command_id`。
- 将重试消息确认发布到延迟队列后，才 ACK 原消息；如果此时崩溃，最多产生重复，不会丢消息。
- 三次自动重试耗尽后，任务与实例进入 `UNKNOWN`，不做错误退款。
- 结构错误、关键字段缺失等不可恢复消息进入死信队列。能从消息中定位任务时将其标记为 `DEAD`；无法定位时只写入死信和系统审计，不猜测业务任务。

### 7.4 人工重试与对账

- 人工重试仅适用于 `UNKNOWN` 任务，重新激活原任务并复用原 `command_id`，同时累加 `manual_retry_count`。
- 人工对账创建 `RECONCILE` 任务，通过 `source_task_id` 引用原任务，调用 gRPC `GetCommandStatus`。
- 引擎返回明确结果时按原操作收敛；引擎仍无事实时保持 `UNKNOWN`。
- 死信重新投递仅限平台管理员，投递前再次校验负载与任务状态。

## 8. REST API 与权限

### 8.1 生命周期接口

```text
POST   /api/v1/instances/{id}/start
POST   /api/v1/instances/{id}/stop
POST   /api/v1/instances/{id}/restart
DELETE /api/v1/instances/{id}?scenario=SUCCESS
POST   /api/v1/instances/batch-actions
```

启动、停止和重启的请求体可包含 `scenario`，默认 `SUCCESS`。所有写接口必须携带 `Idempotency-Key`。

批量操作对整个请求做幂等。服务端按实例逐项执行短事务，忙碌、状态不合法或越权的项单独返回错误，其他项继续。语法合法的批量请求返回 HTTP 200，`data` 中包含成功、跳过、失败数量和每项结果。

### 8.2 任务、审计与 SSE

```text
GET  /api/v1/tasks
GET  /api/v1/tasks/{id}
POST /api/v1/tasks/{id}/retry
POST /api/v1/tasks/{id}/reconcile
POST /api/v1/tasks/{id}/redrive
GET  /api/v1/audit-logs
POST /api/v1/events/tickets
GET  /api/v1/events/stream?ticket=...
```

SSE 凭证为 Redis 中的短期透明票据，绑定用户、租户与权限，不在 URL 中暴露 JWT。票据仅能建立一个活动连接，但在有效期内允许同一用户自动重连；过期后前端重新申请。同一 `EventSource` 重连使用 `Last-Event-ID` 请求头，重建 `EventSource` 时使用 `cursor` query 携带本地记录的最后事件号，补发仍在 7 天保留窗口内的事件。

### 8.3 权限

- `PLATFORM_ADMIN`：查看和管理全部租户数据；可查看及重新投递死信。
- `TENANT_ADMIN`：查看和操作本租户实例；可重试和对账本租户任务；不可处置死信。
- `VIEWER`：仅读本租户实例、任务、账务和监控数据。

非平台管理员查询他租户资源统一返回 404，避免泄露资源是否存在。

## 9. 逻辑删除与数据留痕

- 删除成功后实例进入 `DELETED`，写入 `deleted_at`，默认列表不展示。
- 已删除实例可通过状态筛选查看，不支持恢复或再次操作。
- 订单、钱包流水、异步任务、Inbox、Outbox 和操作审计均保留。
- 本轮不提供物理删除业务数据的对外接口。

## 10. 前端设计

### 10.1 整体体验

- 保留 Vue 3、TypeScript、Element Plus、Pinia 和 ECharts。
- 提供亮色与深色主题切换，使用统一的间距、颜色、圆角、阴影和状态色变量。
- 动效仅用于数字过渡、状态更新、图表进场和抽屉切换，避免阻碍后台操作。
- 路由和 ECharts 组件按需加载，消除当前主包过大的构建警告。

### 10.2 总览大屏

- 指标卡：GPU 总量与可用量、运行实例、任务成功率、异常任务、可用与冻结金额。
- 图表：GPU/CPU/内存趋势、实例状态分布、任务处理趋势。
- 集群健康分布和简化的“集群 → 节点 → GPU”拓扑。
- SSE 到达时增量更新卡片和图表，无需整页刷新。

### 10.3 实例控制台

- 服务端名称、实例号、租户、产品、集群、状态和时间筛选。
- 服务端排序与分页，分页条件保留在 URL query 中。
- 多选后可批量启动、停止、重启和删除，完成后展示逐项结果。
- 操作按钮按实例状态与权限禁用；危险操作需二次确认。
- 实例详情使用抽屉，集中展示基础配置、创建费用、状态时间线、关联任务、操作审计和错误详情。
- 故障场景放在折叠的“高级演示选项”中，默认不干扰正常操作。

### 10.4 异步任务中心

- 按租户、操作类型、任务状态、命令号、实例号和时间筛选。
- 任务详情展示业务事务、Outbox、RabbitMQ、gRPC、回调和收敛六个阶段的时间线。
- `UNKNOWN` 显示“重试”和“对账”；`DEAD` 仅平台管理员显示“重新投递”。
- 显示重试次数、当前延迟、最后错误、消息号、命令号和回调事件号。

### 10.5 其他页面

- 产品、租户与用户页面补齐新增、编辑、启停、角色分配、筛选和分页。
- 费用中心增加流水类型、业务号和时间筛选，并支持跳转到关联实例。
- 全局错误提示显示 `traceId`，并对 401、403、404、409 和服务暂不可用提供明确中文说明。

### 10.6 SSE 客户端

- 登录后申请短期 SSE 票据并建立 `EventSource`。
- 处理实例状态、任务进度、异常告警和集群指标事件。
- 断线使用指数退避自动重连；票据过期时重新申请，并通过 `cursor` 继续最后事件号。
- 连续重连失败后退化为定时刷新，页面显示“非实时”状态。

## 11. 测试设计

### 11.1 单元测试

- 所有合法与非法实例状态转换。
- 操作失败回滚到操作前状态的规则。
- 2、4、8 秒重试策略与最大次数。
- 幂等指纹、回调签名、SSE 票据与租户过滤。
- 创建成功实扣、创建失败解冻与 `UNKNOWN` 继续冻结。

### 11.2 Testcontainers 集成测试

- MySQL + Redis + RabbitMQ 的完整任务链路。
- 同一实例并发不同操作，只有一个任务进入执行。
- 同一幂等键并发提交，只产生一个任务与 Outbox。
- Publisher 重复发布、消费者重复消费和引擎重复回调。
- 发布确认失败、gRPC 三级重试、死信与人工重新投递。
- RabbitMQ、业务服务与 Mock Engine 短暂重启后任务恢复。

### 11.3 gRPC 契约和前端测试

- 每种操作类型的命令序列化、受理和状态查询。
- Mock Engine 的四种故障场景和命令防重。
- Vitest 覆盖权限按钮、状态映射、筛选参数、批量结果、SSE 重连和主题持久化。

### 11.4 Compose 冒烟测试

自动脚本执行：登录 → 充值 → 创建成功实例 → 停止 → 启动 → 重启 → 删除 → 核对任务、账务和审计。另外执行失败、重复回调、超时对账和 RabbitMQ 重启恢复场景。

### 11.5 SQL 与前端构建

- 对实例列表、任务列表、审计列表、Outbox 领取和 SSE 补发的关键 SQL 执行真实 `EXPLAIN`，保存输出并验证索引命中。
- 前端生产构建不得再出现当前主包超过 500 KB 的警告。

## 12. 验收标准

- 创建、启动、停止、重启和删除均能成功完成并展示完整时间线。
- 非法状态操作返回明确冲突信息，不产生新任务。
- 同一实例的并发冲突操作只有一个可提交。
- 相同幂等键并发请求只创建一个任务。
- RabbitMQ、业务服务或 Mock Engine 重启不丢失任务。
- 重复消息、重复命令和重复回调不重复修改状态或账务。
- 重试耗尽后进入 `UNKNOWN`，人工重试、对账或迟到回调可继续收敛。
- 毒消息进入死信，且仅平台管理员可重新投递。
- 创建成功仅产生一次实扣，创建失败仅产生一次解冻，生命周期操作不产生新费用。
- `VIEWER` 所有写操作返回 403，非本租户资源不可读取。
- SSE 断线重连可补发关键事件，完全不可用时前端退化为定时刷新。
- 全新环境只需 Docker 即可按 README 完成启动与演示。

## 13. 分阶段实施与提交

1. **生命周期基础**：迁移、状态机、操作接口、通用 gRPC 协议、Mock Engine 与单元测试。
2. **可靠消息**：RabbitMQ 拓扑、Outbox Publisher、手动 ACK、延迟重试、死信和恢复测试。
3. **实时任务中心**：任务查询、审计、重试、对账、死信处置与 SSE。
4. **前端增强**：实例批量操作、详情时间线、任务中心、服务端筛选分页、CRUD 补齐、主题与看板。
5. **交付完善**：Compose 健康检查、冒烟脚本、SQL `EXPLAIN`、构建优化、README 与五分钟演示脚本。

每个阶段必须保持 `docker compose up --build -d` 可启动，在相应测试通过后生成独立 Git 提交。

## 14. 实施约束

- 保留当前的模块化业务单体，不为了展示 RabbitMQ 而拆分多个空洞微服务。
- 继续使用 MyBatis，不在本轮强行迁移 MyBatis-Plus。
- 不修改历史 Flyway 迁移，所有表结构变更使用新版本。
- 账务流水保持只追加；任何补偿使用新流水，不更改历史流水。
- 实例业务状态以 MySQL 为准，不从 RabbitMQ 队列深度或 Redis 反推业务结果。
- 复用当前统一 API 响应、traceId、JWT、租户隔离和 HMAC 回调规则。
