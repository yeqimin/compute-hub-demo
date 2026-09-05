# ComputeHub Instance Lifecycle, RabbitMQ, and Frontend Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a fully operable instance lifecycle with reliable RabbitMQ delivery, gRPC engine execution, auditable recovery, SSE updates, and an interview-ready Vue administration console.

**Architecture:** Business transactions persist instance, task, audit, realtime, and Outbox records atomically in MySQL. A lease-based publisher sends durable commands to RabbitMQ; a manual-ack consumer invokes the idempotent gRPC Mock Engine, and signed REST callbacks settle the state and creation charge. Vue consumes paginated REST APIs and tenant-scoped SSE events.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AMQP, MyBatis, Flyway, MySQL 8.4, Redis 7.4, RabbitMQ 4, gRPC Java 1.84.0, Vue 3.5.42, TypeScript 5.9.2, Element Plus 2.14.5, Pinia 4.0.3, ECharts 6.1.0, Vitest 4.1.11, Docker Compose, Testcontainers 1.21.3.

**Spec:** `docs/superpowers/specs/2026-09-05-instance-lifecycle-rabbitmq-frontend-design.md`

## Global Constraints

- Keep the modular business monolith; do not split empty microservices.
- Keep MyBatis and add new Flyway migrations instead of changing `V1__schema.sql` or `V2__seed.sql`.
- MySQL remains the source of truth; RabbitMQ and Redis never decide final business state.
- Wallet and audit ledgers are append-only; create is charged once, lifecycle actions are free, and delete does not refund.
- Every lifecycle write requires `Idempotency-Key`; Redis is the fast guard and MySQL is the final uniqueness guard.
- One instance has at most one active lifecycle task; `UNKNOWN` retains that ownership until reconciliation.
- Automatic retries reuse one `command_id` and wait 2, 4, and 8 seconds before becoming `UNKNOWN`.
- All API reads and SSE events enforce the existing platform-admin versus tenant scope.
- `PLATFORM_ADMIN` can redrive dead letters; `TENANT_ADMIN` can operate, retry, and reconcile only its tenant; `VIEWER` is read-only.
- Every milestone ends in a runnable state and a dedicated Git commit authored as yeqimin.

## File and Responsibility Map

### Backend domain and persistence

- `business-server/src/main/resources/db/migration/V3__lifecycle_messaging.sql`: lifecycle columns, task/outbox linkage, audit and realtime tables, query indexes.
- `business-server/src/main/java/com/yeqimin/computehub/domain/InstanceStatus.java`: instance state values.
- `business-server/src/main/java/com/yeqimin/computehub/domain/InstanceOperation.java`: lifecycle operation values and stable source states.
- `business-server/src/main/java/com/yeqimin/computehub/domain/TaskState.java`: asynchronous task states.
- `business-server/src/main/java/com/yeqimin/computehub/domain/EngineScenario.java`: supported Mock Engine scenarios.
- `business-server/src/main/java/com/yeqimin/computehub/domain/TransitionPlan.java`: immutable transition result.
- `business-server/src/main/java/com/yeqimin/computehub/domain/InstanceStateMachine.java`: begin, success, and failure transitions.
- `business-server/src/main/java/com/yeqimin/computehub/persistence/LifecycleMapper.java`: locked instance reads, lifecycle task creation, state compare-and-set, task list/detail.
- `business-server/src/main/java/com/yeqimin/computehub/persistence/OutboxMapper.java`: leased Outbox claiming and publish state changes.
- `business-server/src/main/java/com/yeqimin/computehub/persistence/AuditMapper.java`: append-only audit and realtime events.
- `business-server/src/main/java/com/yeqimin/computehub/persistence/DeadLetterMapper.java`: idempotent dead-letter archive and redrive state.

### Backend application and messaging

- `business-server/src/main/java/com/yeqimin/computehub/instance/LifecycleCommandService.java`: tenant scope, idempotency, batch orchestration.
- `business-server/src/main/java/com/yeqimin/computehub/instance/LifecycleCommandTxService.java`: one-instance locked transaction and Outbox creation.
- `business-server/src/main/java/com/yeqimin/computehub/instance/LifecycleActionRequest.java`: single action scenario request.
- `business-server/src/main/java/com/yeqimin/computehub/instance/BatchInstanceActionRequest.java`: batch request.
- `business-server/src/main/java/com/yeqimin/computehub/instance/BatchInstanceActionResponse.java`: per-item batch result.
- `business-server/src/main/java/com/yeqimin/computehub/instance/LifecycleSubmission.java`: accepted task and instance response.
- `business-server/src/main/java/com/yeqimin/computehub/messaging/EngineCommandMessage.java`: versioned RabbitMQ command envelope.
- `business-server/src/main/java/com/yeqimin/computehub/messaging/RabbitTopologyConfig.java`: exchanges, quorum queue, retry queues, and dead-letter queue.
- `business-server/src/main/java/com/yeqimin/computehub/messaging/OutboxPublisher.java`: claim, publish, confirm, and lease recovery.
- `business-server/src/main/java/com/yeqimin/computehub/messaging/EngineCommandConsumer.java`: task claim, gRPC dispatch, manual ACK, retry, and poison handling.
- `business-server/src/main/java/com/yeqimin/computehub/messaging/EngineCommandDecoder.java`: strict schema/version validation for RabbitMQ payloads.
- `business-server/src/main/java/com/yeqimin/computehub/messaging/PoisonMessageException.java`: non-retryable message validation failure.
- `business-server/src/main/java/com/yeqimin/computehub/messaging/TaskClaim.java`: conditional consumer-claim result.
- `business-server/src/main/java/com/yeqimin/computehub/messaging/RetryPublisher.java`: confirm-before-ack retry publication.
- `business-server/src/main/java/com/yeqimin/computehub/messaging/DeadLetterConsumer.java`: archive dead messages before acknowledging the dead queue.
- `business-server/src/main/java/com/yeqimin/computehub/messaging/DeadLetterRecord.java`: typed archived dead message.
- `business-server/src/main/java/com/yeqimin/computehub/engine/EngineEventRequest.java`: typed signed callback body.
- `business-server/src/main/java/com/yeqimin/computehub/engine/SettlementResult.java`: callback convergence result.
- `business-server/src/main/java/com/yeqimin/computehub/engine/TaskRecoveryService.java`: callback expiry, manual retry, reconciliation, and dead-letter redrive.
- `business-server/src/main/java/com/yeqimin/computehub/engine/TaskSnapshot.java`: typed task, transition, and trace snapshot.
- `business-server/src/main/java/com/yeqimin/computehub/engine/TaskTimeoutScheduler.java`: callback deadline scanning and redispatch.
- `business-server/src/main/java/com/yeqimin/computehub/engine/SettlementService.java`: operation-aware callback settlement.
- `business-server/src/main/java/com/yeqimin/computehub/audit/AuditEntry.java`: typed append-only audit payload.
- `business-server/src/main/java/com/yeqimin/computehub/audit/AuditService.java`: append-only audit events.
- `business-server/src/main/java/com/yeqimin/computehub/realtime/RealtimeEvent.java`: typed durable SSE payload.
- `business-server/src/main/java/com/yeqimin/computehub/realtime/RealtimeEventService.java`: durable tenant events.
- `business-server/src/main/java/com/yeqimin/computehub/realtime/SseTicketService.java`: Redis-backed scoped tickets.
- `business-server/src/main/java/com/yeqimin/computehub/realtime/SsePrincipal.java`: immutable user and tenant scope resolved from a ticket.
- `business-server/src/main/java/com/yeqimin/computehub/realtime/SseConnectionRegistry.java`: in-process tenant emitter registry and after-commit delivery.
- `business-server/src/main/java/com/yeqimin/computehub/realtime/SseController.java`: event replay, heartbeat, and live delivery.

### gRPC and Mock Engine

- `proto/src/main/proto/compute_engine.proto`: generic instance command and operation enum.
- `mock-engine/src/main/java/com/yeqimin/computehub/engine/MockEngineCommandRepository.java`: persistent `command_id` deduplication and recovery reads.
- `mock-engine/src/main/java/com/yeqimin/computehub/engine/MockEngineCommandExecutor.java`: operation/scenario execution.
- `mock-engine/src/main/java/com/yeqimin/computehub/engine/MockEngineCallbackClient.java`: signed callback delivery.
- `mock-engine/src/main/java/com/yeqimin/computehub/engine/MockComputeEngineService.java`: thin gRPC adapter.

### Frontend

- `frontend/src/types/api.ts`: API, instance, task, audit, and paging types.
- `frontend/src/api.ts`: typed response/error handling and lifecycle/task API functions.
- `frontend/src/stores/ui.ts`: theme and realtime connection state.
- `frontend/src/composables/usePageQuery.ts`: URL-backed server paging/filtering.
- `frontend/src/composables/useSseEvents.ts`: ticket acquisition, reconnect, cursor, and polling fallback.
- `frontend/src/utils/permissions.ts`: role/action visibility.
- `frontend/src/utils/instanceActions.ts`: state-to-action rules.
- `frontend/src/views/Instances.vue`: list shell and orchestration.
- `frontend/src/components/instances/InstanceFilters.vue`: instance filter form.
- `frontend/src/components/instances/InstanceTable.vue`: table, selection, and actions.
- `frontend/src/components/instances/InstanceDetailDrawer.vue`: timeline, charge, tasks, and audit.
- `frontend/src/views/Tasks.vue`: task center.
- `frontend/src/components/tasks/TaskDetailDrawer.vue`: six-stage task timeline and recovery actions.
- `frontend/src/views/Dashboard.vue`: realtime KPI, charts, and topology.
- `frontend/src/components/dashboard/MetricCard.vue`: animated KPI value and status tone.
- `frontend/src/views/Products.vue`, `Access.vue`, `Billing.vue`: complete CRUD/filter/paging workflows.

### Test fixture contracts

Test-only helpers used below are local to their named test file; they do not become production APIs. Implement these exact signatures when writing each test:

```java
// LifecycleSchemaMigrationTest
boolean columnExists(String table, String column);
boolean tableExists(String table);

// MockEngineCommandExecutorTest
InstanceCommand command(com.yeqimin.computehub.proto.InstanceOperation operation, String scenario);

// LifecycleConcurrencyTest
record Result(int httpStatus, Long taskId) {}
List<Future<Result>> runTogether(Callable<Result> first, Callable<Result> second);
void runConcurrently(int count, Callable<Result> action);
Result submit(long instanceId, InstanceOperation operation, String key);
String randomKey();
long countActiveTasks(long instanceId);
long countTasks(long instanceId, InstanceOperation operation);

// SettlementServiceTest
EngineEventRequest event(InstanceOperation operation, String result, String instanceStatus);

// RabbitTopologyIntegrationTest
Properties queue(String queueName);

// OutboxPublisherIntegrationTest
long insertReadyOutbox();
long insertExpiredPublishingOutbox();
String outboxState(long outboxId);
String taskStateFor(long outboxId);
List<Message> receivedMessages(long outboxId);

// EngineCommandConsumerIntegrationTest
void publish(EngineCommandMessage message);
void publishRaw(String body, String messageId);
EngineCommandMessage validMessage(int attempt);
void engineAlwaysUnavailable();
String taskState();
long mainQueueMessageCount();
long deadQueueMessageCount();
long deadLetterCount(String messageId);
List<Instant> attemptTimestamps();

// TaskRecoveryServiceTest
TaskSnapshot task(long taskId);
long instanceActiveTask();
void asTenantAdmin(Runnable action);
void createWaitingCallbackTaskWithExpiredDeadline();
List<Integer> redispatchDelays();
String taskState();

// SseReplayIntegrationTest
long appendEvent(long tenantId, String type);
String ticketFor(long tenantId);
String expiredTicket();
List<Long> readEventIds(String ticket, long cursor);
HttpResponse<?> openStream(String ticket);

// AdministrationSecurityTest
HttpResponse<?> postAsViewer(String path, String jsonBody);
String productBody();
String roleBody();
String rechargeBody();
```

Frontend test helpers stay in each Vitest file and expose the exact signatures used by the snippets: `emitEvent(id: string)`, `expireTicket()`, `reconnect(): Promise<void>`, `lastOpenedUrl(): string`, `resolveBatch(result: BatchActionResult)`, `runBatch(action: InstanceAction): Promise<void>`, `openTask(task: AsyncTask): void`, `mountAs(role: RoleCode, task: AsyncTask): void`, `text(selector: string): string`, `emitSse(event: RealtimeEvent): void`, `searchProducts(keyword: string, page: number): Promise<void>`, `lastRequest(): Record<string, unknown>`, and `mountAdminViewsAs(role: RoleCode): void`.

---

## Milestone 1: Lifecycle Domain and Engine Contract

### Task 1: Add lifecycle schema and domain vocabulary

**Files:**
- Create: `business-server/src/main/resources/db/migration/V3__lifecycle_messaging.sql`
- Create: `business-server/src/main/java/com/yeqimin/computehub/domain/InstanceStatus.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/domain/InstanceOperation.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/domain/TaskState.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/domain/EngineScenario.java`
- Test: `business-server/src/test/java/com/yeqimin/computehub/integration/LifecycleSchemaMigrationTest.java`

**Interfaces:**
- Produces: `InstanceStatus`, `InstanceOperation`, `TaskState`, and `EngineScenario` enums used by every later backend task.
- Produces: `compute_instance.active_task_id`, `async_task.operation_type`, direct `outbox_event.task_id`, `operation_audit_log`, `realtime_event`, and `dead_letter_record`.

- [ ] **Step 1: Write a failing Flyway migration integration test**

```java
@Test
void migrationAddsLifecycleColumnsAndEventTables() throws Exception {
  assertThat(columnExists("compute_instance", "active_task_id")).isTrue();
  assertThat(columnExists("async_task", "operation_type")).isTrue();
  assertThat(columnExists("outbox_event", "task_id")).isTrue();
  assertThat(tableExists("operation_audit_log")).isTrue();
  assertThat(tableExists("realtime_event")).isTrue();
  assertThat(tableExists("dead_letter_record")).isTrue();
}
```

- [ ] **Step 2: Run the migration test and verify the missing-schema failure**

Run: `mvn -pl business-server -am -Dtest=LifecycleSchemaMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `V3__lifecycle_messaging.sql` and the new columns do not exist.

- [ ] **Step 3: Add the migration and exact enums**

```java
public enum InstanceStatus {
  REQUESTED, CREATING, RUNNING, STOPPING, STOPPED,
  STARTING, RESTARTING, DELETING, DELETED, FAILED,
  DELETE_FAILED, UNKNOWN
}

public enum InstanceOperation { CREATE, START, STOP, RESTART, DELETE, RECONCILE }
public enum TaskState {
  PENDING, PUBLISHED, PROCESSING, RETRY_WAIT, WAITING_CALLBACK,
  SUCCEEDED, FAILED, UNKNOWN, DEAD
}
public enum EngineScenario { SUCCESS, FAIL, DUPLICATE_CALLBACK, TIMEOUT }
```

Use explicit incremental SQL; the full migration includes every column from the spec and these core constraints:

```sql
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
```

Backfill existing tasks as `CREATE`, set existing Outbox rows to reference their exact task, then make the new required task and Outbox columns non-null.

- [ ] **Step 4: Run the migration and existing backend test suite**

Run: `mvn -pl business-server -am test`

Expected: PASS with the migration test and all existing wallet/idempotency tests green.

- [ ] **Step 5: Commit the schema and vocabulary**

```bash
git add business-server/src/main/resources/db/migration/V3__lifecycle_messaging.sql business-server/src/main/java/com/yeqimin/computehub/domain business-server/src/test/java/com/yeqimin/computehub/integration/LifecycleSchemaMigrationTest.java
git commit -m "feat: add lifecycle task schema"
```

### Task 2: Replace the creation-only state machine

**Files:**
- Create: `business-server/src/main/java/com/yeqimin/computehub/domain/TransitionPlan.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/domain/InstanceStateMachine.java`
- Modify: `business-server/src/test/java/com/yeqimin/computehub/domain/InstanceStateMachineTest.java`

**Interfaces:**
- Consumes: enums from Task 1.
- Produces: `TransitionPlan begin(InstanceStatus, InstanceOperation)`, `InstanceStatus success(TransitionPlan)`, and `InstanceStatus failure(TransitionPlan)`.

- [ ] **Step 1: Write failing table-driven transition tests**

```java
@ParameterizedTest
@CsvSource({
  "REQUESTED,CREATE,CREATING,RUNNING",
  "RUNNING,STOP,STOPPING,STOPPED",
  "STOPPED,START,STARTING,RUNNING",
  "RUNNING,RESTART,RESTARTING,RUNNING",
  "RUNNING,DELETE,DELETING,DELETED",
  "DELETE_FAILED,DELETE,DELETING,DELETED"
})
void lifecycleTransitions(String current, String operation, String executing, String target) {
  TransitionPlan plan = InstanceStateMachine.begin(
      InstanceStatus.valueOf(current), InstanceOperation.valueOf(operation));
  assertThat(plan.executing()).isEqualTo(InstanceStatus.valueOf(executing));
  assertThat(InstanceStateMachine.success(plan)).isEqualTo(InstanceStatus.valueOf(target));
}

@Test
void cannotStartRunningInstance() {
  assertThatThrownBy(() -> InstanceStateMachine.begin(RUNNING, START))
      .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run the state-machine test and verify it fails**

Run: `mvn -pl business-server -am -Dtest=InstanceStateMachineTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because `TransitionPlan` and operation-aware methods are absent.

- [ ] **Step 3: Implement the explicit transition table**

```java
public record TransitionPlan(
    InstanceStatus previous,
    InstanceStatus executing,
    InstanceStatus target,
    InstanceOperation operation) {}

public static TransitionPlan begin(InstanceStatus current, InstanceOperation operation) {
  return switch (operation) {
    case CREATE when current == REQUESTED -> new TransitionPlan(current, CREATING, RUNNING, operation);
    case STOP when current == RUNNING -> new TransitionPlan(current, STOPPING, STOPPED, operation);
    case START when current == STOPPED -> new TransitionPlan(current, STARTING, RUNNING, operation);
    case RESTART when current == RUNNING -> new TransitionPlan(current, RESTARTING, RUNNING, operation);
    case DELETE when Set.of(RUNNING, STOPPED, FAILED, DELETE_FAILED).contains(current)
        -> new TransitionPlan(current, DELETING, DELETED, operation);
    default -> throw new IllegalStateException("illegal lifecycle operation");
  };
}
```

Implement creation failure as `FAILED`, delete failure as `DELETE_FAILED`, and start/stop/restart failure as `plan.previous()`. Reject lifecycle actions from `UNKNOWN` and `DELETED`.

- [ ] **Step 4: Run the complete domain test package**

Run: `mvn -pl business-server -am -Dtest='com.yeqimin.computehub.domain.*Test' -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 5: Commit the state machine**

```bash
git add business-server/src/main/java/com/yeqimin/computehub/domain business-server/src/test/java/com/yeqimin/computehub/domain/InstanceStateMachineTest.java
git commit -m "feat: model full instance lifecycle"
```

### Task 3: Extend the gRPC contract and Mock Engine

**Files:**
- Modify: `proto/src/main/proto/compute_engine.proto`
- Create: `mock-engine/src/main/java/com/yeqimin/computehub/engine/MockEngineCommandRepository.java`
- Create: `mock-engine/src/main/java/com/yeqimin/computehub/engine/MockEngineCommandExecutor.java`
- Create: `mock-engine/src/main/java/com/yeqimin/computehub/engine/MockEngineCallbackClient.java`
- Modify: `mock-engine/src/main/java/com/yeqimin/computehub/engine/MockComputeEngineService.java`
- Test: `mock-engine/src/test/java/com/yeqimin/computehub/engine/MockEngineCommandExecutorTest.java`

**Interfaces:**
- Produces: gRPC `ExecuteInstance(InstanceCommand)` while retaining `CreateInstance` as a compatibility adapter.
- Produces: `MockEngineCommandExecutor.accept(InstanceCommand)` and persistent deduplication by `command_id`.

- [ ] **Step 1: Write failing executor tests for every operation and scenario**

```java
@ParameterizedTest
@EnumSource(value = com.yeqimin.computehub.proto.InstanceOperation.class,
    names = {"CREATE", "START", "STOP", "RESTART", "DELETE"})
void acceptsEachLifecycleOperation(com.yeqimin.computehub.proto.InstanceOperation operation) {
  CommandAccepted accepted = executor.accept(command(operation, "SUCCESS"));
  assertThat(accepted.getAccepted()).isTrue();
}

@Test
void duplicateCommandIsAcceptedWithoutSecondExecution() {
  executor.accept(command(CREATE, "SUCCESS"));
  executor.accept(command(CREATE, "SUCCESS"));
  verify(callbackClient, timeout(2000).times(1)).send(any());
}
```

- [ ] **Step 2: Run the Mock Engine test and verify it fails**

Run: `mvn -pl mock-engine -am -Dtest=MockEngineCommandExecutorTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the generic RPC and focused executor do not exist.

- [ ] **Step 3: Add the generic protobuf contract**

```protobuf
enum InstanceOperation {
  INSTANCE_OPERATION_UNSPECIFIED = 0;
  CREATE = 1; START = 2; STOP = 3; RESTART = 4; DELETE = 5;
}

message InstanceCommand {
  string command_id = 1;
  InstanceOperation operation = 2;
  string instance_no = 3;
  string engine_instance_id = 4;
  int64 tenant_id = 5;
  int64 product_id = 6;
  string cluster_code = 7;
  string scenario = 8;
  string callback_url = 9;
}
```

Add `rpc ExecuteInstance(InstanceCommand) returns (CommandAccepted);` and include operation plus final instance state in `CommandStatusReply`.

- [ ] **Step 4: Split persistence, execution, and callback delivery**

The gRPC service must delegate immediately. The repository uses `INSERT IGNORE` on `command_id`; the executor maps success to `RUNNING`, `STOPPED`, `RUNNING`, or `DELETED` according to the operation. `FAIL` sends a failed result, `DUPLICATE_CALLBACK` sends the same `engine_event_id` twice, and `TIMEOUT` persists a result without a callback.

```java
@Override
public void executeInstance(InstanceCommand command, StreamObserver<CommandAccepted> observer) {
  observer.onNext(executor.accept(command));
  observer.onCompleted();
}

private String successfulState(InstanceOperation operation) {
  return switch (operation) {
    case CREATE, START, RESTART -> "RUNNING";
    case STOP -> "STOPPED";
    case DELETE -> "DELETED";
    default -> throw new IllegalArgumentException("unsupported operation");
  };
}
```

- [ ] **Step 5: Run proto and Mock Engine tests**

Run: `mvn -pl mock-engine -am test`

Expected: PASS, including persisted-command recovery tests.

- [ ] **Step 6: Commit the engine contract**

```bash
git add proto/src/main/proto/compute_engine.proto mock-engine/src/main mock-engine/src/test
git commit -m "feat: support lifecycle commands in mock engine"
```

### Task 4: Implement idempotent lifecycle submission and batch actions

**Files:**
- Create: `business-server/src/main/java/com/yeqimin/computehub/instance/LifecycleActionRequest.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/instance/BatchInstanceActionRequest.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/instance/BatchInstanceActionResponse.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/instance/LifecycleSubmission.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/instance/LifecycleCommandService.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/instance/LifecycleCommandTxService.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/persistence/LifecycleMapper.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/persistence/AuditMapper.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/audit/AuditEntry.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/audit/AuditService.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/realtime/RealtimeEvent.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/realtime/RealtimeEventService.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/engine/TaskSnapshot.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/instance/InstanceService.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/instance/InstanceTxService.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/instance/InstanceController.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/instance/InstanceMapper.java`
- Test: `business-server/src/test/java/com/yeqimin/computehub/integration/LifecycleConcurrencyTest.java`

**Interfaces:**
- Produces: `LifecycleSubmission submit(String key, long instanceId, InstanceOperation operation, EngineScenario scenario)`.
- Produces: `BatchInstanceActionResponse submitBatch(String key, BatchInstanceActionRequest request)`.
- Produces: REST start, stop, restart, delete, and batch endpoints from the spec.
- Produces: mapper methods `instanceForUpdate`, `insertLifecycleTask`, `setActiveTask`, `insertCommandOutbox`, and `submission` used by the transaction service.

- [ ] **Step 1: Write failing concurrency and idempotency tests**

```java
@Test
void onlyOneConflictingOperationOwnsTheInstance() throws Exception {
  List<Future<Result>> results = runTogether(
      () -> submit(instanceId, STOP, randomKey()),
      () -> submit(instanceId, RESTART, randomKey()));
  assertThat(results).extracting(Result::httpStatus)
      .containsExactlyInAnyOrder(200, 409);
  assertThat(countActiveTasks(instanceId)).isEqualTo(1);
}

@Test
void sameIdempotencyKeyCreatesOneTask() {
  runConcurrently(50, () -> submit(instanceId, STOP, "same-key"));
  assertThat(countTasks(instanceId, STOP)).isEqualTo(1);
}
```

- [ ] **Step 2: Run the integration test and verify it fails**

Run: `mvn -pl business-server -am -Dtest=LifecycleConcurrencyTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because lifecycle endpoints and active-task locking are absent.

- [ ] **Step 3: Implement the locked lifecycle transaction**

```java
public record LifecycleSubmission(
    long instanceId,
    long taskId,
    String taskNo,
    String commandId,
    InstanceStatus instanceStatus,
    TaskState taskState) {}

public record LifecycleActionRequest(EngineScenario scenario) {
  public LifecycleActionRequest {
    scenario = scenario == null ? EngineScenario.SUCCESS : scenario;
  }
}

public record BatchInstanceActionRequest(
    List<Long> instanceIds, InstanceOperation action, EngineScenario scenario) {}

public record BatchInstanceActionResponse(
    int successCount, int skippedCount, int failedCount,
    List<Item> items) {
  public record Item(long instanceId, String code, String message, Long taskId) {}
}

public record AuditEntry(
    Long tenantId, Long actorId, Long instanceId, Long taskId,
    String action, String beforeState, String afterState,
    String result, String error, String traceId) {}

public record RealtimeEvent(
    long id, Long tenantId, String type,
    String aggregateType, long aggregateId, String payload) {}

public record TaskSnapshot(
    long id, long instanceId, long tenantId, String commandId,
    InstanceOperation operation, TaskState state,
    InstanceStatus previousStatus, InstanceStatus executingStatus,
    InstanceStatus targetStatus, int retryCount, String traceId) {
  public boolean terminal() {
    return Set.of(TaskState.SUCCEEDED, TaskState.FAILED, TaskState.DEAD).contains(state);
  }
  public TransitionPlan transitionPlan() {
    return new TransitionPlan(previousStatus, executingStatus, targetStatus, operation);
  }
}

@Transactional
public LifecycleSubmission submit(
    long actorId, long tenantId, long instanceId,
    InstanceOperation operation, EngineScenario scenario,
    String idempotencyKey, String requestHash) {
  Map<String, Object> instance = mapper.instanceForUpdate(instanceId);
  TransitionPlan plan = InstanceStateMachine.begin(status(instance), operation);
  Map<String, Object> task = new HashMap<>();
  task.put("taskNo", "TASK-" + UUID.randomUUID().toString().replace("-", ""));
  task.put("commandId", "CMD-" + UUID.randomUUID().toString().replace("-", ""));
  task.put("tenantId", tenantId);
  task.put("instanceId", instanceId);
  task.put("operation", operation.name());
  task.put("scenario", scenario.name());
  task.put("previousStatus", plan.previous().name());
  task.put("targetStatus", plan.target().name());
  mapper.insertLifecycleTask(task);
  long taskId = ((Number) task.get("id")).longValue();
  mapper.setActiveTask(instanceId, taskId, plan.executing());
  mapper.insertCommandOutbox(taskId, String.valueOf(task.get("commandId")), instance, operation, scenario);
  audit.appendAccepted(actorId, tenantId, instanceId, taskId, operation, plan);
  realtime.appendTaskChanged(tenantId, instanceId, taskId);
  return mapper.submission(taskId);
}
```

Use one short transaction per batch item. The batch endpoint returns HTTP 200 with `successCount`, `skippedCount`, `failedCount`, and a result for every requested instance.

- [ ] **Step 4: Add controller methods and permission checks**

Use `instance:operate` for start/stop/restart/delete and existing `instance:create` for creation. Derive child request fingerprints from the batch key, operation, instance ID, and scenario.

```java
@PostMapping("/{id}/stop")
@PreAuthorize("hasAuthority('instance:operate')")
public ApiResponse<?> stop(
    @PathVariable long id,
    @RequestHeader("Idempotency-Key") String key,
    @Valid @RequestBody LifecycleActionRequest request) {
  return ApiResponse.ok(lifecycle.submit(key, id, InstanceOperation.STOP, request.scenario()));
}

@PostMapping("/batch-actions")
@PreAuthorize("hasAuthority('instance:operate')")
public ApiResponse<?> batch(
    @RequestHeader("Idempotency-Key") String key,
    @Valid @RequestBody BatchInstanceActionRequest request) {
  return ApiResponse.ok(lifecycle.submitBatch(key, request));
}
```

Refactor `InstanceTxService.create` to populate the new `CREATE` task fields and `active_task_id` in the original wallet-freeze transaction. Extend instance list queries with keyword, tenant, product, cluster, state, start/end time, sort, and paging. Exclude `DELETED` unless the caller explicitly filters for `DELETED`; settlement sets `deleted_at` when delete succeeds.

- [ ] **Step 5: Run lifecycle and existing wallet concurrency tests**

Run: `mvn -pl business-server -am -Dtest='LifecycleConcurrencyTest,WalletConcurrencyTest' -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS with one active task and no wallet regression.

- [ ] **Step 6: Commit lifecycle submission**

```bash
git add business-server/src/main/java/com/yeqimin/computehub/instance business-server/src/main/java/com/yeqimin/computehub/persistence business-server/src/main/java/com/yeqimin/computehub/audit business-server/src/main/java/com/yeqimin/computehub/realtime business-server/src/test/java/com/yeqimin/computehub/integration/LifecycleConcurrencyTest.java
git commit -m "feat: add idempotent lifecycle operations"
```

### Task 5: Generalize callbacks and settlement

**Files:**
- Create: `business-server/src/main/java/com/yeqimin/computehub/engine/EngineEventRequest.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/engine/SettlementResult.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/engine/EngineCallbackController.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/engine/SettlementService.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/persistence/BillingMapper.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/persistence/TaskMapper.java`
- Test: `business-server/src/test/java/com/yeqimin/computehub/engine/SettlementServiceTest.java`

**Interfaces:**
- Produces: `SettlementResult settle(EngineEventRequest event, String payloadHash)`.
- Consumes: the task transition snapshot and unique `engine_event_id`.

- [ ] **Step 1: Write failing settlement tests**

```java
@Test
void stopSuccessDoesNotTouchWallet() {
  SettlementResult result = settlement.settle(event(STOP, "SUCCEEDED", "STOPPED"), hash);
  assertThat(result.instanceStatus()).isEqualTo(STOPPED);
  verifyNoInteractions(billingMapper);
}

@Test
void lateCreateSuccessDeductsFrozenAmountOnce() {
  settlement.settle(event(CREATE, "SUCCEEDED", "RUNNING"), hash);
  settlement.settle(event(CREATE, "SUCCEEDED", "RUNNING"), hash);
  verify(billingMapper, times(1)).insertLedger(any());
}
```

- [ ] **Step 2: Run the settlement tests and verify they fail**

Run: `mvn -pl business-server -am -Dtest=SettlementServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because settlement only understands create-to-running/failed.

- [ ] **Step 3: Add the typed callback and operation-aware settlement**

```java
public record EngineEventRequest(
    String eventId,
    String commandId,
    InstanceOperation operation,
    String result,
    InstanceStatus instanceStatus,
    String engineInstanceId,
    String message) {}

public record SettlementResult(
    boolean duplicate,
    long instanceId,
    long taskId,
    InstanceStatus instanceStatus,
    TaskState taskState) {}

@Transactional
public SettlementResult settle(EngineEventRequest event, String payloadHash) {
  if (lifecycle.insertInbox(event, payloadHash) == 0) return lifecycle.previousResult(event.commandId());
  TaskSnapshot task = lifecycle.taskForUpdate(event.commandId());
  if (task.terminal()) return lifecycle.recordConflictingCallback(task, event);
  InstanceStatus next = "SUCCEEDED".equals(event.result())
      ? InstanceStateMachine.success(task.transitionPlan())
      : InstanceStateMachine.failure(task.transitionPlan());
  if (task.operation() == InstanceOperation.CREATE) settleCreationWallet(task, event.result());
  lifecycle.finishTaskAndInstance(task, event, next);
  return new SettlementResult(false, task.instanceId(), task.id(), next,
      "SUCCEEDED".equals(event.result()) ? TaskState.SUCCEEDED : TaskState.FAILED);
}
```

In one transaction: insert Inbox, lock task and instance, verify operation, settle creation money only, change task and instance, clear `active_task_id` on explicit terminal results, and append audit/realtime events. A conflicting callback after a terminal result records audit only.

- [ ] **Step 4: Run callback, signature, wallet, and settlement tests**

Run: `mvn -pl business-server -am -Dtest='SettlementServiceTest,CallbackSignerTest,WalletConcurrencyTest' -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 5: Commit generalized settlement**

```bash
git add business-server/src/main/java/com/yeqimin/computehub/engine business-server/src/main/java/com/yeqimin/computehub/persistence business-server/src/test/java/com/yeqimin/computehub/engine
git commit -m "feat: settle lifecycle callbacks safely"
```

## Milestone 2: RabbitMQ Reliable Delivery

### Task 6: Add RabbitMQ topology and container support

**Files:**
- Modify: `business-server/pom.xml`
- Modify: `business-server/src/main/resources/application.yml`
- Create: `business-server/src/main/java/com/yeqimin/computehub/messaging/RabbitTopologyConfig.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/messaging/EngineCommandMessage.java`
- Modify: `docker-compose.yml`
- Modify: `README.md`
- Test: `business-server/src/test/java/com/yeqimin/computehub/integration/RabbitTopologyIntegrationTest.java`

**Interfaces:**
- Produces: durable `compute.command.x`, quorum `compute.command.q`, 2/4/8-second retry queues, and `compute.command.dead.q`.
- Produces: versioned JSON `EngineCommandMessage` with `schemaVersion = 1`.

- [ ] **Step 1: Write a failing topology integration test**

```java
@Test
void declaresMainRetryAndDeadQueues() {
  assertThat(queue("compute.command.q")).isNotNull();
  assertThat(queue("compute.command.retry.2s.q")).isNotNull();
  assertThat(queue("compute.command.retry.4s.q")).isNotNull();
  assertThat(queue("compute.command.retry.8s.q")).isNotNull();
  assertThat(queue("compute.command.dead.q")).isNotNull();
}
```

- [ ] **Step 2: Add Spring AMQP and RabbitMQ Testcontainers dependencies**

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>rabbitmq</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Run the topology test and verify it fails before configuration exists**

Run: `mvn -pl business-server -am -Dtest=RabbitTopologyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because the topology is not declared.

- [ ] **Step 4: Implement topology and Compose configuration**

Add `rabbitmq:4-management-alpine`, `RABBITMQ_DEFAULT_USER=compute`, `RABBITMQ_DEFAULT_PASS=compute123`, health check `rabbitmq-diagnostics -q ping`, host port `5673`, management port `15673`, and make `business-server` depend on RabbitMQ health. Retry queues use fixed TTL and dead-letter back to the main exchange without the delayed-message plugin.

```yaml
rabbitmq:
  image: rabbitmq:4-management-alpine
  environment:
    RABBITMQ_DEFAULT_USER: compute
    RABBITMQ_DEFAULT_PASS: compute123
  ports: ["5673:5672", "15673:15672"]
  healthcheck:
    test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
    interval: 5s
    timeout: 5s
    retries: 20
```

```java
public record EngineCommandMessage(
    int schemaVersion, String messageId, long taskId, String commandId,
    long instanceId, String instanceNo, String engineInstanceId,
    long tenantId, long productId, String clusterCode,
    InstanceOperation operation, EngineScenario scenario,
    String callbackUrl, int attempt) {
  public EngineCommandMessage nextAttempt() {
    return new EngineCommandMessage(schemaVersion, messageId, taskId, commandId,
        instanceId, instanceNo, engineInstanceId, tenantId, productId, clusterCode,
        operation, scenario, callbackUrl, attempt + 1);
  }
}

@Bean
Queue retry2sQueue() {
  return QueueBuilder.durable("compute.command.retry.2s.q")
      .ttl(2_000)
      .deadLetterExchange("compute.command.x")
      .deadLetterRoutingKey("command.execute")
      .build();
}
```

- [ ] **Step 5: Run the topology test**

Run: `mvn -pl business-server -am -Dtest=RabbitTopologyIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 6: Commit RabbitMQ infrastructure**

```bash
git add business-server/pom.xml business-server/src/main/resources/application.yml business-server/src/main/java/com/yeqimin/computehub/messaging docker-compose.yml README.md business-server/src/test/java/com/yeqimin/computehub/integration/RabbitTopologyIntegrationTest.java
git commit -m "feat: add rabbitmq command topology"
```

### Task 7: Replace direct Outbox dispatch with confirmed publication

**Files:**
- Create: `business-server/src/main/java/com/yeqimin/computehub/persistence/OutboxMapper.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/messaging/OutboxPublisher.java`
- Delete: `business-server/src/main/java/com/yeqimin/computehub/engine/OutboxWorker.java`
- Test: `business-server/src/test/java/com/yeqimin/computehub/integration/OutboxPublisherIntegrationTest.java`

**Interfaces:**
- Produces: `List<OutboxLease> claimReady(int batchSize, Duration lease)`.
- Produces: `void publishReady()` scheduled publisher and `handleConfirm(CorrelationData.Confirm)` callback.

- [ ] **Step 1: Write failing publish-confirm and lease-recovery tests**

```java
@Test
void confirmMarksExactOutboxAndTaskPublished() {
  long outboxId = insertReadyOutbox();
  publisher.publishReady();
  await().untilAsserted(() -> assertThat(outboxState(outboxId)).isEqualTo("SENT"));
  assertThat(taskStateFor(outboxId)).isEqualTo("PUBLISHED");
}

@Test
void expiredPublishingLeaseIsClaimedAgain() {
  long outboxId = insertExpiredPublishingOutbox();
  publisher.publishReady();
  assertThat(receivedMessages(outboxId)).hasSize(1);
}
```

- [ ] **Step 2: Run the publisher integration test and verify it fails**

Run: `mvn -pl business-server -am -Dtest=OutboxPublisherIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because Outbox still calls gRPC directly.

- [ ] **Step 3: Implement lease-based claim and publisher confirms**

```java
public record OutboxLease(
    long outboxId, long taskId, String commandId,
    String messageId, String publishToken, String payload) {}

@Scheduled(fixedDelayString = "${compute-hub.outbox.publish-delay-ms:250}")
public void publishReady() {
  for (OutboxLease lease : leaseService.claimReady(50, Duration.ofSeconds(30))) {
    CorrelationData correlation = new CorrelationData(
        lease.outboxId() + ":" + lease.publishToken());
    rabbitTemplate.convertAndSend("compute.command.x", "command.execute",
        lease.payload(), message -> {
          message.getMessageProperties().setMessageId(lease.messageId());
          message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
          return message;
        }, correlation);
  }
}
```

Claim at most 50 rows with `FOR UPDATE SKIP LOCKED`. Commit the claim before network I/O. Correlation data contains outbox ID plus publish token so stale confirms cannot update a reclaimed lease. Returned or NACKed messages go back to `READY` with an error.

- [ ] **Step 4: Run publisher and lifecycle integration tests**

Run: `mvn -pl business-server -am -Dtest='OutboxPublisherIntegrationTest,LifecycleConcurrencyTest' -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 5: Commit the confirmed publisher**

```bash
git add business-server/src/main/java/com/yeqimin/computehub/persistence/OutboxMapper.java business-server/src/main/java/com/yeqimin/computehub/messaging business-server/src/main/java/com/yeqimin/computehub/engine/OutboxWorker.java business-server/src/test/java/com/yeqimin/computehub/integration/OutboxPublisherIntegrationTest.java
git commit -m "feat: publish outbox commands reliably"
```

### Task 8: Add manual-ack consumer, retries, and poison handling

**Files:**
- Create: `business-server/src/main/java/com/yeqimin/computehub/messaging/EngineCommandConsumer.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/messaging/EngineCommandDecoder.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/messaging/PoisonMessageException.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/messaging/TaskClaim.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/messaging/RetryPublisher.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/messaging/DeadLetterConsumer.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/messaging/DeadLetterRecord.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/persistence/DeadLetterMapper.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/engine/EngineClient.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/persistence/TaskMapper.java`
- Test: `business-server/src/test/java/com/yeqimin/computehub/integration/EngineCommandConsumerIntegrationTest.java`

**Interfaces:**
- Produces: `CommandAccepted execute(EngineCommandMessage message)` and `CommandStatusReply status(String commandId)`.
- Produces: `void consume(Message raw, Channel channel, long deliveryTag)` with manual ACK.
- Produces: `void publishAndConfirm(EngineCommandMessage message)` that routes by `message.attempt()` and `void deadLetterAndConfirm(Message raw, String reason)` that confirms dead-letter publication before ACKing the original.
- Produces: `void archive(Message raw, Channel channel, long deliveryTag)` with idempotent `dead_letter_record.message_id` storage.
- Produces: `EngineCommandMessage decodeStrict(Message raw)` and `TaskClaim(boolean alreadyHandled, TaskSnapshot task)`.

- [ ] **Step 1: Write failing consumer tests**

```java
@Test
void acceptedCommandIsAcknowledgedAfterTaskWaitsForCallback() {
  publish(validMessage(0));
  await().untilAsserted(() -> assertThat(taskState()).isEqualTo("WAITING_CALLBACK"));
  assertThat(mainQueueMessageCount()).isZero();
}

@Test
void transientFailuresUseTwoFourEightSecondRetriesThenUnknown() {
  engineAlwaysUnavailable();
  publish(validMessage(0));
  await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
      assertThat(taskState()).isEqualTo("UNKNOWN"));
  assertThat(attemptTimestamps()).hasSize(4);
}

@Test
void poisonMessageIsArchivedOnceBeforeDeadQueueAck() {
  publishRaw("not-json", "poison-message-1");
  publishRaw("not-json", "poison-message-1");
  await().untilAsserted(() -> assertThat(deadLetterCount("poison-message-1")).isEqualTo(1));
  assertThat(deadQueueMessageCount()).isZero();
}
```

- [ ] **Step 2: Run the consumer test and verify it fails**

Run: `mvn -pl business-server -am -Dtest=EngineCommandConsumerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because no RabbitMQ consumer exists.

- [ ] **Step 3: Implement conditional task claim and generic gRPC dispatch**

Deserialize strictly. Unknown schema versions and missing identifiers are poison. Atomically change only `PUBLISHED` or `RETRY_WAIT` to `PROCESSING`; ACK messages for tasks already in `WAITING_CALLBACK` or a terminal state.

```java
public record TaskClaim(boolean alreadyHandled, TaskSnapshot task) {}

public record DeadLetterRecord(
    String messageId, Long taskId, Long tenantId,
    String rawPayload, String headersJson,
    String failureReason, String state) {}

@RabbitListener(queues = "compute.command.q", ackMode = "MANUAL")
public void consume(Message raw, Channel channel,
                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
  EngineCommandMessage command;
  try {
    command = decoder.decodeStrict(raw);
  } catch (PoisonMessageException poison) {
    retryPublisher.deadLetterAndConfirm(raw, poison.getMessage());
    channel.basicAck(deliveryTag, false);
    return;
  }
  TaskClaim claim = tasks.claimForProcessing(command.taskId(), command.commandId());
  if (claim.alreadyHandled()) {
    channel.basicAck(deliveryTag, false);
    return;
  }
  dispatchOrRetry(command, raw, channel, deliveryTag);
}
```

- [ ] **Step 4: Implement confirmed delayed retry and dead-letter routing**

Route attempts 1, 2, and 3 to the 2/4/8-second queues. After the last failed gRPC attempt, set task and instance to `UNKNOWN` and ACK. Poison messages go to the dead exchange. `DeadLetterConsumer` stores the raw body, headers, message ID, parseable task/tenant IDs, and failure reason in `dead_letter_record`, conditionally marks a resolvable task `DEAD`, then ACKs the dead queue message. Duplicate dead deliveries are ignored by the unique message ID.

```java
private void dispatchOrRetry(EngineCommandMessage command, Message raw,
                             Channel channel, long deliveryTag) throws IOException {
  try {
    CommandAccepted accepted = engine.execute(command);
    tasks.waitForCallback(command.taskId(), accepted.getMessage());
    channel.basicAck(deliveryTag, false);
  } catch (StatusRuntimeException transientFailure) {
    if (command.attempt() >= 3) {
      tasks.markUnknown(command.taskId(), command.instanceId(), transientFailure.getMessage());
      channel.basicAck(deliveryTag, false);
    } else {
      retryPublisher.publishAndConfirm(command.nextAttempt());
      channel.basicAck(deliveryTag, false);
    }
  }
}
```

- [ ] **Step 5: Run consumer, publisher, and Mock Engine tests**

Run: `mvn -pl business-server,mock-engine -am test`

Expected: PASS.

- [ ] **Step 6: Commit reliable consumption**

```bash
git add business-server/src/main/java/com/yeqimin/computehub/messaging business-server/src/main/java/com/yeqimin/computehub/engine/EngineClient.java business-server/src/main/java/com/yeqimin/computehub/persistence/TaskMapper.java business-server/src/main/java/com/yeqimin/computehub/persistence/DeadLetterMapper.java business-server/src/test/java/com/yeqimin/computehub/integration/EngineCommandConsumerIntegrationTest.java
git commit -m "feat: consume engine commands with reliable retries"
```

## Milestone 3: Task Center, Audit, and Realtime Updates

### Task 9: Add task queries, retry, reconciliation, and redrive

**Files:**
- Create: `business-server/src/main/java/com/yeqimin/computehub/engine/TaskQuery.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/engine/TaskSnapshot.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/engine/TaskRecoveryService.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/engine/TaskTimeoutScheduler.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/engine/TaskController.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/persistence/LifecycleMapper.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/persistence/TaskMapper.java`
- Test: `business-server/src/test/java/com/yeqimin/computehub/engine/TaskRecoveryServiceTest.java`

**Interfaces:**
- Produces: paged `GET /api/v1/tasks` and detailed `GET /api/v1/tasks/{id}`.
- Produces: `retry(long taskId)`, `reconcile(long taskId)`, and platform-only `redrive(long taskId)`.

- [ ] **Step 1: Write failing recovery and permission tests**

```java
@Test
void reconcileCreatesOneChildTaskWithoutReplacingSourceLock() {
  long childId = service.reconcile(unknownTaskId);
  assertThat(task(childId).sourceTaskId()).isEqualTo(unknownTaskId);
  assertThat(instanceActiveTask()).isEqualTo(unknownTaskId);
}

@Test
void tenantAdminCannotRedriveDeadLetter() {
  assertThatThrownBy(() -> asTenantAdmin(() -> service.redrive(deadTaskId)))
      .isInstanceOf(AccessDeniedException.class);
}

@Test
void callbackTimeoutsRedispatchAtTwoFourEightSecondsThenBecomeUnknown() {
  createWaitingCallbackTaskWithExpiredDeadline();
  scheduler.scanExpiredCallbacks();
  assertThat(redispatchDelays()).containsExactly(2, 4, 8);
  assertThat(taskState()).isEqualTo("UNKNOWN");
}
```

- [ ] **Step 2: Run recovery tests and verify they fail**

Run: `mvn -pl business-server -am -Dtest=TaskRecoveryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because recovery is still a direct controller/gRPC operation.

- [ ] **Step 3: Implement paged task query and recovery services**

Queries accept tenant, operation, task state, command ID, instance number, start time, end time, page, and size. Manual retry reuses the source command; reconciliation creates one child task with `source_task_id`; redrive reads an archived `dead_letter_record`, validates its message version, task, and tenant, then publishes a new message ID and records `redriven_at`. Unresolvable dead letters remain read-only. `TaskTimeoutScheduler` scans expired `WAITING_CALLBACK` rows with `FOR UPDATE SKIP LOCKED`, resets the same Outbox command for 2/4/8-second redispatch, and moves the source task plus instance to `UNKNOWN` after the third callback timeout.

```java
public record TaskQuery(
    Long tenantId, InstanceOperation operation, TaskState state,
    String commandId, String instanceNo,
    Instant startedAt, Instant endedAt, int page, int size) {}

@Scheduled(fixedDelayString = "${compute-hub.task-timeout-scan-ms:1000}")
public void scanExpiredCallbacks() {
  for (TaskSnapshot task : tasks.claimExpiredCallbacks(20)) {
    if (task.retryCount() >= 3) tasks.markUnknown(task.id(), task.instanceId(), "callback timeout");
    else tasks.scheduleOutboxRedispatch(task.id(), RetryPolicy.delaySeconds(task.retryCount() + 1));
  }
}
```

- [ ] **Step 4: Run task recovery and tenant-scope tests**

Run: `mvn -pl business-server -am -Dtest='TaskRecoveryServiceTest,*SecurityTest' -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 5: Commit task recovery APIs**

```bash
git add business-server/src/main/java/com/yeqimin/computehub/engine business-server/src/main/java/com/yeqimin/computehub/persistence business-server/src/test/java/com/yeqimin/computehub/engine/TaskRecoveryServiceTest.java
git commit -m "feat: add task center recovery APIs"
```

### Task 10: Add append-only audit and durable SSE

**Files:**
- Modify: `business-server/src/main/java/com/yeqimin/computehub/persistence/AuditMapper.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/audit/AuditService.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/audit/AuditController.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/realtime/RealtimeEventService.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/realtime/SseTicketService.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/realtime/SsePrincipal.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/realtime/SseConnectionRegistry.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/realtime/SseController.java`
- Test: `business-server/src/test/java/com/yeqimin/computehub/integration/SseReplayIntegrationTest.java`

**Interfaces:**
- Produces: `void appendAudit(AuditEntry entry)` and `long appendEvent(RealtimeEvent event)`.
- Produces: `POST /api/v1/events/tickets`, `GET /api/v1/events/stream?ticket={ticket}&cursor={eventId}`, and paged `GET /api/v1/audit-logs`.
- Produces: `SsePrincipal(long userId, Long tenantId, boolean platformAdmin)`.

- [ ] **Step 1: Write failing SSE scope and replay tests**

```java
@Test
void reconnectReplaysOnlyTenantEventsAfterCursor() {
  long cursor = appendEvent(tenantA, "INSTANCE_CHANGED");
  long expected = appendEvent(tenantA, "TASK_CHANGED");
  appendEvent(tenantB, "TASK_CHANGED");
  assertThat(readEventIds(ticketFor(tenantA), cursor)).containsExactly(expected);
}

@Test
void expiredTicketIsRejected() {
  String ticket = expiredTicket();
  assertThat(openStream(ticket).statusCode()).isEqualTo(401);
}
```

- [ ] **Step 2: Run the SSE integration test and verify it fails**

Run: `mvn -pl business-server -am -Dtest=SseReplayIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL because ticket validation, replay, and live SSE connections do not exist.

- [ ] **Step 3: Implement scoped tickets, replay, and heartbeat**

Store opaque ticket metadata in Redis, bind it to user and tenant scope, allow one active connection, and allow reconnect by that user until expiry. Replay `realtime_event.id > cursor` in ascending order, then register the emitter in `SseConnectionRegistry` and keep the connection open with 15-second heartbeat events. `RealtimeEventService` publishes an application event only after the database transaction commits; the registry sends it to matching tenant emitters. Delete database events older than seven days with a scheduled cleanup.

```java
public record SsePrincipal(long userId, Long tenantId, boolean platformAdmin) {}

@GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestParam String ticket,
                         @RequestParam(defaultValue = "0") long cursor) {
  SsePrincipal principal = tickets.requireValid(ticket);
  SseEmitter emitter = new SseEmitter(0L);
  events.replay(principal, cursor).forEach(event -> registry.send(emitter, event));
  registry.register(principal, emitter);
  return emitter;
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void afterCommit(RealtimeEvent event) {
  registry.broadcast(event);
}
```

- [ ] **Step 4: Wire audit/realtime writes into lifecycle and settlement transactions**

Extend the Task 4 audit/realtime writers so every publish transition, retry, callback, conflict, reconciliation, and redrive appends an audit entry. Business-visible changes append a realtime event in the same MySQL transaction.

```java
@Transactional
public void recordTaskChange(TaskSnapshot task, String action, String result, String error) {
  AuditEntry audit = new AuditEntry(task.tenantId(), null, task.instanceId(), task.id(),
      action, task.previousStatus().name(), task.targetStatus().name(),
      result, error, task.traceId());
  auditMapper.insert(audit);
  String payload;
  try {
    payload = objectMapper.writeValueAsString(task);
  } catch (JsonProcessingException exception) {
    throw new IllegalStateException("cannot serialize realtime event", exception);
  }
  RealtimeEvent pending = new RealtimeEvent(0, task.tenantId(), "TASK_CHANGED",
      "TASK", task.id(), payload);
  long eventId = auditMapper.insertRealtime(pending);
  applicationEvents.publishEvent(new RealtimeEvent(eventId, pending.tenantId(),
      pending.type(), pending.aggregateType(), pending.aggregateId(), pending.payload()));
}
```

- [ ] **Step 5: Run SSE, lifecycle, and settlement tests**

Run: `mvn -pl business-server -am -Dtest='SseReplayIntegrationTest,LifecycleConcurrencyTest,SettlementServiceTest' -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

- [ ] **Step 6: Commit audit and SSE**

```bash
git add business-server/src/main/java/com/yeqimin/computehub/audit business-server/src/main/java/com/yeqimin/computehub/realtime business-server/src/main/java/com/yeqimin/computehub/persistence/AuditMapper.java business-server/src/test/java/com/yeqimin/computehub/integration/SseReplayIntegrationTest.java
git commit -m "feat: stream durable tenant task events"
```

## Milestone 4: Frontend Administration Experience

### Task 11: Add typed API, permissions, theme, and lazy routes

**Files:**
- Create: `frontend/src/types/api.ts`
- Create: `frontend/src/stores/ui.ts`
- Create: `frontend/src/utils/permissions.ts`
- Create: `frontend/src/utils/instanceActions.ts`
- Create: `frontend/src/composables/usePageQuery.ts`
- Create: `frontend/src/composables/useSseEvents.ts`
- Create: `frontend/src/auto-imports.d.ts` (generated by Vite plugin and committed)
- Create: `frontend/src/components.d.ts` (generated by Vite plugin and committed)
- Modify: `frontend/src/api.ts`
- Modify: `frontend/src/main.ts`
- Modify: `frontend/src/router.ts`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/styles.css`
- Modify: `frontend/vite.config.ts`
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Test: `frontend/src/utils/instanceActions.spec.ts`
- Test: `frontend/src/composables/useSseEvents.spec.ts`

**Interfaces:**
- Produces: typed `Page<T>`, `InstanceSummary`, `AsyncTask`, `AuditLog`, and `BatchActionResult`.
- Produces: `allowedActions(status, permissions)` and `useSseEvents(onEvent)`.

- [ ] **Step 1: Write failing permission and reconnect tests**

```ts
it('allows stop and restart only for a running writable instance', () => {
  expect(allowedActions('RUNNING', ['instance:operate'])).toEqual(['STOP', 'RESTART', 'DELETE'])
  expect(allowedActions('RUNNING', [])).toEqual([])
})

it('reopens with the saved cursor after ticket expiry', async () => {
  emitEvent('42')
  expireTicket()
  await reconnect()
  expect(lastOpenedUrl()).toContain('cursor=42')
})
```

- [ ] **Step 2: Run the frontend tests and verify they fail**

Run: `cd frontend && npm test -- src/utils/instanceActions.spec.ts src/composables/useSseEvents.spec.ts`

Expected: FAIL because the new utilities do not exist.

- [ ] **Step 3: Implement typed API and UI infrastructure**

Replace `api: any` with `AxiosInstance`; throw a typed `ApiError` containing status, code, message, and traceId. Persist `light`, `dark`, or `system` theme in Pinia. Store the latest SSE event ID in session storage and switch to visible polling fallback after repeated reconnect failures.

Run: `cd frontend && npm install -D unplugin-auto-import unplugin-vue-components`

Remove global `app.use(ElementPlus)` registration. Configure `ElementPlusResolver` so used components and styles are imported per SFC; keep the Chinese locale only where date/pagination components require it.

```ts
export interface Page<T> { items: T[]; total: number; page: number; size: number }
export type InstanceStatus =
  | 'REQUESTED' | 'CREATING' | 'RUNNING' | 'STOPPING' | 'STOPPED'
  | 'STARTING' | 'RESTARTING' | 'DELETING' | 'DELETED'
  | 'FAILED' | 'DELETE_FAILED' | 'UNKNOWN'
export type TaskState =
  | 'PENDING' | 'PUBLISHED' | 'PROCESSING' | 'RETRY_WAIT'
  | 'WAITING_CALLBACK' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN' | 'DEAD'
export type InstanceAction = 'START' | 'STOP' | 'RESTART' | 'DELETE'
export type RoleCode = 'PLATFORM_ADMIN' | 'TENANT_ADMIN' | 'VIEWER'
export interface InstanceSummary {
  id: number; instanceNo: string; tenantId: number; name: string
  productName: string; clusterName: string; status: InstanceStatus
  taskId?: number; createdAt: string; deletedAt?: string
}
export interface RealtimeEvent<T = unknown> {
  id: string
  type: string
  aggregateType: 'INSTANCE' | 'TASK' | 'DASHBOARD' | 'CLUSTER'
  aggregateId: number
  payload: T
}
export interface AsyncTask {
  id: number; taskNo: string; commandId: string; instanceId: number
  operation: 'CREATE' | InstanceAction | 'RECONCILE'; state: TaskState
  retryCount: number; lastError?: string
}
export interface AuditLog {
  id: number; tenantId?: number; actorId?: number; instanceId?: number; taskId?: number
  action: string; beforeState?: string; afterState?: string
  result: string; error?: string; traceId?: string; createdAt: string
}
export interface BatchActionResult {
  successCount: number; skippedCount: number; failedCount: number
  items: Array<{ instanceId: number; code: string; message: string; taskId?: number }>
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly traceId?: string,
  ) { super(message) }
}

export function allowedActions(status: InstanceStatus, permissions: string[]): InstanceAction[] {
  if (!permissions.includes('instance:operate')) return []
  if (status === 'RUNNING') return ['STOP', 'RESTART', 'DELETE']
  if (status === 'STOPPED') return ['START', 'DELETE']
  if (status === 'FAILED' || status === 'DELETE_FAILED') return ['DELETE']
  return []
}
```

```ts
// vite.config.ts
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()], dts: 'src/auto-imports.d.ts' }),
    Components({ resolvers: [ElementPlusResolver()], dts: 'src/components.d.ts' }),
  ],
})
```

- [ ] **Step 4: Lazy-load routes and ECharts modules**

```ts
const Dashboard = () => import('./views/Dashboard.vue')
const Instances = () => import('./views/Instances.vue')
const Tasks = () => import('./views/Tasks.vue')
```

Register only the ECharts charts and renderers used by each page. Add the Task Center menu with role-independent read visibility.

- [ ] **Step 5: Run tests, typecheck, and production build**

Run: `cd frontend && npm test && npm run build`

Expected: PASS with no chunk over 500 KB warning.

- [ ] **Step 6: Commit frontend infrastructure**

```bash
git add frontend/src frontend/package.json frontend/package-lock.json
git commit -m "refactor: add typed realtime frontend foundation"
```

### Task 12: Rebuild the instance console

**Files:**
- Modify: `frontend/src/views/Instances.vue`
- Create: `frontend/src/components/instances/InstanceFilters.vue`
- Create: `frontend/src/components/instances/InstanceTable.vue`
- Create: `frontend/src/components/instances/InstanceDetailDrawer.vue`
- Test: `frontend/src/views/Instances.spec.ts`

**Interfaces:**
- Consumes: typed lifecycle APIs, `allowedActions`, URL page query, and SSE instance/task events.
- Produces: server filters, paging, single and batch actions, advanced scenario selection, and detail timeline.

- [ ] **Step 1: Write failing component tests**

```ts
it('keeps filters in the URL and requests server paging', async () => {
  await wrapper.find('[data-test=status-filter]').setValue('RUNNING')
  await wrapper.find('[data-test=search]').setValue('gpu-demo')
  await wrapper.find('[data-test=query]').trigger('click')
  expect(router.currentRoute.value.query).toMatchObject({ status: 'RUNNING', keyword: 'gpu-demo', page: '1' })
})

it('shows every batch item result', async () => {
  resolveBatch({ successCount: 1, skippedCount: 1, failedCount: 1, items: threeResults })
  await runBatch('STOP')
  expect(wrapper.findAll('[data-test=batch-result]')).toHaveLength(3)
})
```

- [ ] **Step 2: Run the instance view test and verify it fails**

Run: `cd frontend && npm test -- src/views/Instances.spec.ts`

Expected: FAIL because the page still polls a simple status list and lacks components.

- [ ] **Step 3: Implement filters, table, action dialogs, and paging**

Use server filters for keyword, tenant, product, cluster, status, and time. Preserve query state in the URL. Confirm delete and batch delete. Keep the scenario selector collapsed under “高级演示选项” with `SUCCESS` as default.

```vue
<InstanceFilters v-model="query" @search="search" @reset="reset" />
<InstanceTable
  :rows="page.items"
  :loading="loading"
  :permissions="auth.user?.permissions ?? []"
  @selection-change="selected = $event"
  @action="openAction"
  @detail="openDetail"
/>
<el-pagination
  v-model:current-page="query.page"
  v-model:page-size="query.size"
  :total="page.total"
  @change="load"
/>
```

- [ ] **Step 4: Implement the detail drawer**

Display configuration, creation charge, lifecycle timeline, related tasks, audit rows, command IDs, retries, and errors. Update the open row and drawer when matching SSE events arrive.

```ts
const onRealtimeEvent = async (event: RealtimeEvent) => {
  if (event.aggregateType === 'INSTANCE') replaceRow(event.payload as InstanceSummary)
  if (detail.value?.id === event.aggregateId) detail.value = await instanceApi.get(event.aggregateId)
}
```

- [ ] **Step 5: Run instance tests and frontend build**

Run: `cd frontend && npm test -- src/views/Instances.spec.ts && npm run build`

Expected: PASS.

- [ ] **Step 6: Commit the instance console**

```bash
git add frontend/src/views/Instances.vue frontend/src/components/instances frontend/src/views/Instances.spec.ts
git commit -m "feat: build full instance operations console"
```

### Task 13: Build the asynchronous task center

**Files:**
- Create: `frontend/src/views/Tasks.vue`
- Create: `frontend/src/components/tasks/TaskDetailDrawer.vue`
- Create: `frontend/src/components/tasks/TaskTimeline.vue`
- Modify: `frontend/src/router.ts`
- Modify: `frontend/src/App.vue`
- Test: `frontend/src/views/Tasks.spec.ts`

**Interfaces:**
- Consumes: task query/detail/retry/reconcile/redrive APIs and task SSE events.
- Produces: paged task center with role-aware recovery actions.

- [ ] **Step 1: Write failing task-center tests**

```ts
it('shows the six processing phases in task details', async () => {
  openTask(taskWithFullHistory)
  expect(wrapper.findAll('[data-test=task-phase]')).toHaveLength(6)
})

it('shows redrive only to platform admins for dead tasks', async () => {
  mountAs('TENANT_ADMIN', deadTask)
  expect(wrapper.find('[data-test=redrive]').exists()).toBe(false)
  mountAs('PLATFORM_ADMIN', deadTask)
  expect(wrapper.find('[data-test=redrive]').exists()).toBe(true)
})
```

- [ ] **Step 2: Run task-center tests and verify they fail**

Run: `cd frontend && npm test -- src/views/Tasks.spec.ts`

Expected: FAIL because the route and components are absent.

- [ ] **Step 3: Implement server filters, summary cards, and task timeline**

Filters cover tenant, operation, state, command ID, instance number, and time. The detail drawer shows business transaction, Outbox, RabbitMQ, gRPC, callback, and convergence phases.

```vue
<el-table :data="page.items" v-loading="loading" row-key="id">
  <el-table-column prop="taskNo" label="任务号" min-width="190" />
  <el-table-column prop="operation" label="操作" />
  <el-table-column prop="state" label="状态" />
  <el-table-column prop="retryCount" label="自动重试" />
  <el-table-column prop="lastError" label="最后错误" show-overflow-tooltip />
</el-table>
<TaskDetailDrawer v-model="drawer" :task-id="selectedTaskId" />
```

- [ ] **Step 4: Add recovery actions and SSE updates**

Show retry/reconcile only for `UNKNOWN`; show redrive only for platform admins and `DEAD`. Require confirmation and display the returned traceId when an action fails.

```ts
const recoveryActions = computed(() => {
  if (task.value?.state === 'UNKNOWN') return ['RETRY', 'RECONCILE'] as const
  if (task.value?.state === 'DEAD' && auth.isAdmin) return ['REDRIVE'] as const
  return [] as const
})

const onTaskEvent = (event: RealtimeEvent) => {
  if (event.type === 'TASK_CHANGED') upsertTask(event.payload as AsyncTask)
}
```

- [ ] **Step 5: Run task-center tests and build**

Run: `cd frontend && npm test -- src/views/Tasks.spec.ts && npm run build`

Expected: PASS.

- [ ] **Step 6: Commit the task center**

```bash
git add frontend/src/views/Tasks.vue frontend/src/components/tasks frontend/src/router.ts frontend/src/App.vue frontend/src/views/Tasks.spec.ts
git commit -m "feat: add realtime asynchronous task center"
```

### Task 14: Upgrade the dashboard and resource visualization

**Files:**
- Modify: `business-server/src/main/java/com/yeqimin/computehub/dashboard/DashboardController.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/persistence/DashboardMapper.java`
- Modify: `frontend/src/views/Dashboard.vue`
- Create: `frontend/src/components/dashboard/ClusterTopology.vue`
- Create: `frontend/src/components/dashboard/MetricTrend.vue`
- Create: `frontend/src/components/dashboard/MetricCard.vue`
- Test: `frontend/src/views/Dashboard.spec.ts`

**Interfaces:**
- Produces: dashboard summary with task success rate, abnormal task count, instance distribution, and topology nodes/edges.
- Consumes: cluster metric and realtime events.

- [ ] **Step 1: Write failing dashboard tests**

```ts
it('updates a KPI from an SSE summary event without a page reload', async () => {
  expect(text('[data-test=running-instances]')).toBe('3')
  emitSse({ type: 'DASHBOARD_SUMMARY', payload: { runningInstances: 4 } })
  expect(text('[data-test=running-instances]')).toBe('4')
})
```

- [ ] **Step 2: Run the dashboard test and verify it fails**

Run: `cd frontend && npm test -- src/views/Dashboard.spec.ts`

Expected: FAIL because current metrics rely on interval refresh and lack the new KPIs/topology.

- [ ] **Step 3: Add dashboard response fields and indexed queries**

Return GPU capacity, active instances, task success rate, abnormal tasks, wallet totals, instance distribution, cluster health, and simplified cluster/node/GPU topology. Apply tenant scope to every business count.

```java
@Select("""
  SELECT
    COUNT(CASE WHEN state='SUCCEEDED' THEN 1 END) successfulTasks,
    COUNT(CASE WHEN state IN ('UNKNOWN','DEAD') THEN 1 END) abnormalTasks,
    COUNT(*) totalTasks
  FROM async_task
  WHERE (#{tenantId} IS NULL OR tenant_id=#{tenantId})
    AND created_at >= DATE_SUB(NOW(3), INTERVAL 24 HOUR)
  """)
Map<String, Object> taskSummary(Long tenantId);
```

- [ ] **Step 4: Implement the responsive dashboard**

Use animated KPI values, ECharts trend/distribution charts, compact topology, explicit loading skeletons, empty states, and restrained transitions. Incrementally apply SSE payloads and perform a full refresh when the connection resumes after fallback polling.

```vue
<section class="kpi-grid" v-loading="loading">
  <MetricCard data-test="running-instances" label="运行实例" :value="summary.runningInstances" />
  <MetricCard label="任务成功率" :value="summary.taskSuccessRate" suffix="%" />
  <MetricCard label="异常任务" :value="summary.abnormalTasks" tone="danger" />
</section>
<MetricTrend :points="metricPoints" />
<ClusterTopology :clusters="summary.topology" />
```

- [ ] **Step 5: Run dashboard tests and build**

Run: `cd frontend && npm test -- src/views/Dashboard.spec.ts && npm run build`

Expected: PASS with no oversized chunk warning.

- [ ] **Step 6: Commit dashboard enhancements**

```bash
git add business-server/src/main/java/com/yeqimin/computehub/dashboard business-server/src/main/java/com/yeqimin/computehub/persistence/DashboardMapper.java frontend/src/views/Dashboard.vue frontend/src/components/dashboard frontend/src/views/Dashboard.spec.ts
git commit -m "feat: add realtime compute operations dashboard"
```

### Task 15: Complete product, access, and billing administration

**Files:**
- Create: `business-server/src/main/java/com/yeqimin/computehub/catalog/ProductUpdateRequest.java`
- Create: `business-server/src/main/java/com/yeqimin/computehub/auth/AssignRolesRequest.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/catalog/CatalogController.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/catalog/CatalogService.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/auth/AuthController.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/auth/AuthService.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/billing/BillingController.java`
- Modify: `business-server/src/main/java/com/yeqimin/computehub/billing/BillingService.java`
- Modify: `frontend/src/views/Products.vue`
- Modify: `frontend/src/views/Access.vue`
- Modify: `frontend/src/views/Billing.vue`
- Test: `business-server/src/test/java/com/yeqimin/computehub/integration/AdministrationSecurityTest.java`
- Test: `frontend/src/views/AdminCrud.spec.ts`

**Interfaces:**
- Produces: server-filtered paged product, tenant, user, and ledger responses.
- Produces: product create/edit/enable, user role assignment, ledger filter, and related-instance navigation.

- [ ] **Step 1: Write failing CRUD, permission, and paging tests**

```ts
it('submits the product search and requested server page', async () => {
  await searchProducts('H100', 2)
  expect(lastRequest()).toMatchObject({ keyword: 'H100', page: 2, size: 20 })
})

it('viewer never sees mutation buttons', () => {
  mountAdminViewsAs('VIEWER')
  expect(wrapper.findAll('[data-test=mutation]').length).toBe(0)
})
```

- [ ] **Step 2: Run the administration test and verify it fails**

Run: `cd frontend && npm test -- src/views/AdminCrud.spec.ts`

Expected: FAIL because filters, paging, and mutations are incomplete.

- [ ] **Step 3: Write and run backend tenant and permission tests**

```java
@Test
void viewerCannotMutateProductsUsersOrWallet() {
  assertThat(postAsViewer("/api/v1/products", productBody()).statusCode()).isEqualTo(403);
  assertThat(postAsViewer("/api/v1/users/2/roles", roleBody()).statusCode()).isEqualTo(403);
  assertThat(postAsViewer("/api/v1/wallet/recharges", rechargeBody()).statusCode()).isEqualTo(403);
}
```

Run: `mvn -pl business-server -am -Dtest=AdministrationSecurityTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL until the new mutations and consistent permission rules exist.

- [ ] **Step 4: Complete backend list filters and mutations**

Use existing RBAC annotations and tenant scoping. Product delete remains out of scope; enabled status is the lifecycle control. Users are disabled instead of physically deleted. Ledger records remain immutable.

```java
public record ProductUpdateRequest(
    String name, String gpuModel, int gpuCount,
    int cpuCores, int memoryGb, long priceCent, boolean enabled) {}

public record AssignRolesRequest(Set<String> roleCodes) {}

@PutMapping("/products/{id}")
@PreAuthorize("hasAuthority('product:manage')")
public ApiResponse<?> updateProduct(@PathVariable long id,
                                    @Valid @RequestBody ProductUpdateRequest request) {
  return ApiResponse.ok(catalog.updateProduct(id, request));
}

@PutMapping("/users/{id}/roles")
@PreAuthorize("hasAuthority('tenant:manage')")
public ApiResponse<?> assignRoles(@PathVariable long id,
                                  @Valid @RequestBody AssignRolesRequest request) {
  return ApiResponse.ok(auth.assignRoles(id, request.roleCodes()));
}
```

- [ ] **Step 5: Complete frontend forms, paging, and related navigation**

Add validation, loading state, empty state, mutation confirmation, server paging, and query reset. Ledger business numbers link to the related instance drawer when one exists.

```vue
<el-table :data="page.items" v-loading="loading">
  <el-table-column prop="sku" label="SKU" />
  <el-table-column prop="name" label="产品名称" />
  <el-table-column prop="enabled" label="状态" />
  <el-table-column v-if="canMutate" label="操作">
    <template #default="{ row }">
      <el-button data-test="mutation" link @click="edit(row)">编辑</el-button>
    </template>
  </el-table-column>
</el-table>
<el-pagination :total="page.total" v-model:current-page="query.page" @change="load" />
```

- [ ] **Step 6: Run backend security tests, all frontend tests, and build**

Run: `mvn -pl business-server -am -Dtest=AdministrationSecurityTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS.

Run: `cd frontend && npm test && npm run build`

Expected: PASS.

- [ ] **Step 7: Commit administration enhancements**

```bash
git add business-server/src/main/java/com/yeqimin/computehub/catalog business-server/src/main/java/com/yeqimin/computehub/auth business-server/src/main/java/com/yeqimin/computehub/billing business-server/src/test/java/com/yeqimin/computehub/integration/AdministrationSecurityTest.java frontend/src/views frontend/src/views/AdminCrud.spec.ts
git commit -m "feat: complete administration workflows"
```

## Milestone 5: Verification and Interview Delivery

### Task 16: Add end-to-end recovery, SQL evidence, and documentation

**Files:**
- Modify: `scripts/smoke-test.sh`
- Modify: `scripts/acceptance-test.mjs`
- Modify: `scripts/recovery-test.mjs`
- Create: `scripts/lib/demo-client.mjs`
- Create: `scripts/lifecycle-test.mjs`
- Create: `scripts/rabbitmq-recovery-test.mjs`
- Modify: `docs/sql-performance.md`
- Modify: `docs/architecture.md`
- Modify: `README.md`
- Modify: `.github/workflows/ci.yml`
- Modify: `Makefile`

**Interfaces:**
- Produces: `make lifecycle`, `make rabbit-recovery`, and expanded `make acceptance` verification commands.
- Produces: documented RabbitMQ console URL, complete five-minute demo, and actual MySQL EXPLAIN evidence.
- Produces: shared script functions `login`, `request`, `createInstance`, `instanceAction`, `waitForInstance`, `listLedgers`, and `waitForHealth`.

- [ ] **Step 1: Extend smoke tests before changing documentation**

```js
import {
  login, createInstance, instanceAction, waitForInstance, listLedgers,
} from './lib/demo-client.mjs'

const token = await login('tenant_admin', 'Tenant@123')
const created = await createInstance(token, 'SUCCESS', `lifecycle-${Date.now()}`)
await waitForInstance(token, created.id, 'RUNNING')
await instanceAction(token, created.id, 'stop', 'SUCCESS')
await waitForInstance(token, created.id, 'STOPPED')
await instanceAction(token, created.id, 'start', 'SUCCESS')
await instanceAction(token, created.id, 'restart', 'DUPLICATE_CALLBACK')
await instanceAction(token, created.id, 'delete', 'SUCCESS')
await waitForInstance(token, created.id, 'DELETED')
const deductions = (await listLedgers(token))
  .filter(row => row.bizNo === created.orderNo && row.type === 'DEDUCT')
if (deductions.length !== 1) throw new Error(`expected one deduction, got ${deductions.length}`)
```

- [ ] **Step 2: Run the new scripts against Compose and record the first failure**

Run: `docker compose up --build -d && node scripts/lifecycle-test.mjs && node scripts/rabbitmq-recovery-test.mjs`

Expected: PASS. Any failure is a release blocker and must be corrected in the owning product layer or deterministic readiness check; do not add arbitrary sleeps.

- [ ] **Step 3: Add recovery assertions**

The recovery script must pause/restart RabbitMQ, business-server, and mock-engine at separate points, then assert eventual terminal or `UNKNOWN` state, one engine command per command ID, and no duplicate wallet ledger.

```js
import { execFileSync } from 'node:child_process'
import { waitForHealth } from './lib/demo-client.mjs'

function compose(...args) {
  execFileSync('docker', ['compose', ...args], { stdio: 'inherit' })
}

function queryScalar(sql) {
  return Number(execFileSync('docker', [
    'compose', 'exec', '-T', 'mysql', 'mysql',
    '-ucompute', '-pcompute123', 'compute_hub', '-Nse', sql,
  ], { encoding: 'utf8' }).trim())
}

compose('restart', 'rabbitmq')
await waitForHealth('business-server')
const afterBrokerRecovery = await waitForInstance(token, created.id, 'RUNNING')
if (afterBrokerRecovery.status !== 'RUNNING') throw new Error('outbox did not recover')
compose('restart', 'mock-engine')
const commandCount = queryScalar(
  `SELECT COUNT(*) FROM mock_engine_command WHERE command_id='${created.commandId}'`)
if (commandCount !== 1) throw new Error(`expected one engine command, got ${commandCount}`)
const ledgerCount = queryScalar(
  `SELECT COUNT(*) FROM wallet_ledger WHERE biz_no='${created.orderNo}' AND type='DEDUCT'`)
if (ledgerCount !== 1) throw new Error(`expected one deduction, got ${ledgerCount}`)
```

- [ ] **Step 4: Capture real EXPLAIN results**

Run `EXPLAIN ANALYZE` for instance filtering, task filtering, audit filtering, Outbox claim, and SSE replay against seeded data. Save query, parameters, chosen key, rows examined, and observed plan in `docs/sql-performance.md`. Adjust `V3` indexes only through a new `V4__query_index_adjustments.sql` migration if verification reveals a mismatch.

```sql
EXPLAIN ANALYZE
SELECT id, instance_no, status, created_at
FROM compute_instance
WHERE tenant_id = 2 AND status = 'RUNNING' AND deleted_at IS NULL
ORDER BY created_at DESC, id DESC
LIMIT 20;

EXPLAIN ANALYZE
SELECT id, task_no, operation_type, state, created_at
FROM async_task
WHERE tenant_id = 2 AND state = 'UNKNOWN'
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

- [ ] **Step 5: Update CI, architecture, README, and demo script**

CI runs full Maven tests, frontend tests/build, and Compose smoke. README documents ports `8088`, `8080`, `9090`, `3307`, `6380`, `5673`, and `15673`, credentials, task-center demonstration, RabbitMQ retry/dead-letter inspection, and cleanup.

```make
.PHONY: lifecycle rabbit-recovery
lifecycle:
	node ./scripts/lifecycle-test.mjs
rabbit-recovery:
	node ./scripts/rabbitmq-recovery-test.mjs
```

```yaml
- name: Full backend tests
  run: mvn test
- name: Frontend tests and build
  working-directory: frontend
  run: npm ci --legacy-peer-deps && npm test && npm run build
- name: Compose smoke
  run: docker compose up --build -d && ./scripts/smoke-test.sh
```

- [ ] **Step 6: Run the complete verification gate**

Run: `make test`

Expected: Maven, Testcontainers, Vitest, typecheck, and production build all exit 0.

Run: `make smoke && make concurrency && make acceptance && make lifecycle && make rabbit-recovery`

Expected: every script exits 0 and prints its final PASS summary.

Run: `docker compose ps`

Expected: MySQL, Redis, RabbitMQ, business-server, Mock Engine, and frontend are running; services with health checks report healthy.

- [ ] **Step 7: Review the final diff for secrets and generated artifacts**

Run: `git status --short && git diff --check && git grep -nE '(BEGIN (RSA|OPENSSH) PRIVATE KEY|AKIA[0-9A-Z]{16})' -- . ':!package-lock.json'`

Expected: only intentional source/document changes are present, `git diff --check` is clean, and the secret scan prints no matches.

- [ ] **Step 8: Commit delivery verification**

```bash
git add scripts docs README.md .github/workflows/ci.yml Makefile business-server/src/main/resources/db/migration
git commit -m "test: verify lifecycle messaging demo end to end"
```

## Final Acceptance Checklist

- [ ] All sixteen tasks have dedicated passing evidence and commits.
- [ ] The repository is clean after the final commit.
- [ ] `docker compose up --build -d` works on a Docker-only machine.
- [ ] The success, failure, duplicate callback, timeout, reconciliation, and dead-letter demonstrations are documented and repeatable.
- [ ] Wallet snapshots reconcile with immutable ledger entries after every create scenario.
- [ ] Cross-tenant REST and SSE reads are rejected.
- [ ] The frontend presents server paging, batch partial results, detail timelines, loading/empty/error states, dark mode, and realtime fallback status.
- [ ] The frontend build has no chunk-over-500-KB warning.
- [ ] Actual EXPLAIN evidence names the indexes used by the five critical query families.
