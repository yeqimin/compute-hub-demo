package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.common.BusinessException;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.TaskState;
import com.yeqimin.computehub.persistence.InstanceMapper;
import com.yeqimin.computehub.persistence.TaskMapper;
import com.yeqimin.computehub.proto.CommandStatusReply;
import com.yeqimin.computehub.security.UserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LifecycleRecoveryTest {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8")
      .withDatabaseName("test");

  private static final AtomicLong IDS = new AtomicLong(40_000);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @MockitoBean OutboxWorker scheduledOutboxWorker;
  @MockitoBean TaskTimeoutScheduler scheduledTaskTimeoutScheduler;
  @MockitoBean EngineClient engine;
  @Autowired TaskMapper tasks;
  @Autowired InstanceMapper instances;
  @Autowired TaskController controller;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void resetDataAndAuthenticate() {
    jdbc.update("DELETE FROM realtime_event");
    jdbc.update("DELETE FROM operation_audit_log");
    jdbc.update("DELETE FROM inbox_event");
    jdbc.update("DELETE FROM outbox_event");
    jdbc.update("DELETE FROM async_task");
    jdbc.update("DELETE FROM compute_instance");
    jdbc.update("DELETE FROM compute_order");
    authenticateTenant(2L);
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void expiredTaskSelectsOnlyItsOwnOutbox() {
    Fixture target = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.STOPPING, InstanceStatus.STOPPED, TaskState.WAITING_CALLBACK, 0,
        "WAITING_CALLBACK", true);
    long historicalTaskId = insertTask(target.instanceId(), 2L, InstanceOperation.RESTART,
        InstanceStatus.RUNNING, InstanceStatus.RUNNING, TaskState.SUCCEEDED, 0, false);
    insertOutbox(target.instanceId(), historicalTaskId, "DONE");

    List<Map<String, Object>> expired = tasks.expiredTasks();

    assertThat(expired).hasSize(1);
    assertThat(number(expired.getFirst(), "taskId")).isEqualTo(target.taskId());
    assertThat(number(expired.getFirst(), "outboxId")).isEqualTo(target.outboxId());
  }

  @Test
  void manualAndCompletionOutboxUpdatesUseTaskIdNotInstanceId() {
    Fixture target = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 2,
        "DEAD", false);
    long historicalTaskId = insertTask(target.instanceId(), 2L, InstanceOperation.RESTART,
        InstanceStatus.RUNNING, InstanceStatus.RUNNING, TaskState.SUCCEEDED, 0, false);
    long historicalOutboxId = insertOutbox(target.instanceId(), historicalTaskId, "DEAD");

    assertThat(tasks.manualOutbox(target.taskId())).isEqualTo(1);
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", target.outboxId()))
        .isEqualTo("READY");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", historicalOutboxId))
        .isEqualTo("DEAD");

    assertThat(tasks.outboxDone(target.taskId())).isEqualTo(1);
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", target.outboxId()))
        .isEqualTo("DONE");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", historicalOutboxId))
        .isEqualTo("DEAD");
  }

  @ParameterizedTest
  @MethodSource("operations")
  void callbackTimeoutMovesEveryExecutingOperationToUnknown(
      InstanceOperation operation,
      InstanceStatus previous,
      InstanceStatus executing,
      InstanceStatus target) {
    Fixture fixture = fixture(2L, operation, previous, executing, target,
        TaskState.WAITING_CALLBACK, 3, "WAITING_CALLBACK", true);
    OutboxWorker worker = new OutboxWorker(tasks, engine);

    worker.expireCallbacks();

    assertThat(value("SELECT state FROM async_task WHERE id=?", fixture.taskId()))
        .isEqualTo("UNKNOWN");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", fixture.outboxId()))
        .isEqualTo("DEAD");
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("UNKNOWN");
  }

  @ParameterizedTest
  @MethodSource("operations")
  void manualRetryRestoresOperationExecutingStatusAndIncrementsManualCount(
      InstanceOperation operation,
      InstanceStatus previous,
      InstanceStatus executing,
      InstanceStatus target) {
    Fixture fixture = fixture(2L, operation, previous, InstanceStatus.UNKNOWN, target,
        TaskState.UNKNOWN, 2, "DEAD", false);
    when(engine.status(fixture.commandId())).thenReturn(status(
        fixture.commandId(), "PROCESSING", operation, "", ""));

    controller.retry(fixture.taskId());

    assertThat(row("SELECT state,retry_count,manual_retry_count FROM async_task WHERE id=?",
        fixture.taskId()))
        .containsEntry("state", "READY")
        .containsEntry("retry_count", 0)
        .containsEntry("manual_retry_count", 1);
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", fixture.outboxId()))
        .isEqualTo("READY");
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo(executing.name());
  }

  @Test
  void missingEngineCommandRetriesTheExactUnknownTaskWithoutSettlement() {
    Fixture fixture = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 2, "DEAD", false);
    long historicalTaskId = insertTask(fixture.instanceId(), 2L, InstanceOperation.RESTART,
        InstanceStatus.RUNNING, InstanceStatus.RUNNING, TaskState.UNKNOWN, 2, false);
    long historicalOutboxId = insertOutbox(fixture.instanceId(), historicalTaskId, "DEAD");
    when(engine.status(fixture.commandId())).thenReturn(missingStatus(fixture.commandId()));

    controller.retry(fixture.taskId());

    assertThat(row("SELECT state,retry_count,manual_retry_count,command_id FROM async_task WHERE id=?",
        fixture.taskId()))
        .containsEntry("state", "READY")
        .containsEntry("retry_count", 0)
        .containsEntry("manual_retry_count", 1)
        .containsEntry("command_id", fixture.commandId());
    assertThat(row("SELECT state,command_id FROM outbox_event WHERE id=?", fixture.outboxId()))
        .containsEntry("state", "READY")
        .containsEntry("command_id", fixture.commandId());
    assertThat(value("SELECT state FROM async_task WHERE id=?", historicalTaskId))
        .isEqualTo("UNKNOWN");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", historicalOutboxId))
        .isEqualTo("DEAD");
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("STOPPING");
    assertThat(count("SELECT COUNT(*) FROM inbox_event")).isZero();
  }

  @Test
  void missingEngineCommandStillRequiresAnExactCommandId() {
    Fixture fixture = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 2, "DEAD", false);
    when(engine.status(fixture.commandId())).thenReturn(missingStatus("CMD-DIFFERENT"));

    assertThatThrownBy(() -> controller.reconcile(fixture.taskId()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("命令");
    assertThat(value("SELECT state FROM async_task WHERE id=?", fixture.taskId()))
        .isEqualTo("UNKNOWN");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", fixture.outboxId()))
        .isEqualTo("DEAD");
    assertThat(count("SELECT COUNT(*) FROM inbox_event")).isZero();
  }

  @Test
  void missingEngineCommandCannotRetryANonUnknownTask() {
    Fixture fixture = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.STOPPING, InstanceStatus.STOPPED, TaskState.PENDING, 1, "READY", false);
    when(engine.status(fixture.commandId())).thenReturn(missingStatus(fixture.commandId()));

    assertThatThrownBy(() -> controller.retry(fixture.taskId()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无需人工重试");
    assertThat(row("SELECT state,retry_count,manual_retry_count FROM async_task WHERE id=?",
        fixture.taskId()))
        .containsEntry("state", "PENDING")
        .containsEntry("retry_count", 1)
        .containsEntry("manual_retry_count", 0);
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("STOPPING");
  }

  @Test
  void missingEngineCommandCannotCrossTenantBoundary() {
    Fixture fixture = fixture(1L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 2, "DEAD", false);

    assertThatThrownBy(() -> controller.retry(fixture.taskId()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("任务不存在");
    assertThat(value("SELECT state FROM async_task WHERE id=?", fixture.taskId()))
        .isEqualTo("UNKNOWN");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", fixture.outboxId()))
        .isEqualTo("DEAD");
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("UNKNOWN");
  }

  @Test
  void missingEngineCommandRecoveryRollsBackThroughSpringProxyWhenTaskIsNotActive() {
    Fixture fixture = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 2, "DEAD", false);
    long activeTaskId = insertTask(fixture.instanceId(), 2L, InstanceOperation.RESTART,
        InstanceStatus.RUNNING, InstanceStatus.RUNNING, TaskState.UNKNOWN, 2, false);
    jdbc.update("UPDATE compute_instance SET active_task_id=? WHERE id=?",
        activeTaskId, fixture.instanceId());
    when(engine.status(fixture.commandId())).thenReturn(missingStatus(fixture.commandId()));

    assertThatThrownBy(() -> controller.retry(fixture.taskId()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("恢复状态已变更");

    assertThat(row("SELECT state,retry_count,manual_retry_count FROM async_task WHERE id=?",
        fixture.taskId()))
        .containsEntry("state", "UNKNOWN")
        .containsEntry("retry_count", 2)
        .containsEntry("manual_retry_count", 0);
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", fixture.outboxId()))
        .isEqualTo("DEAD");
    assertThat(number(row("SELECT active_task_id FROM compute_instance WHERE id=?",
        fixture.instanceId()), "active_task_id")).isEqualTo(activeTaskId);
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("UNKNOWN");
  }

  @Test
  void reconciliationRejectsAnEngineCommandFromAnotherTenant() {
    Fixture requested = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 2, "DEAD", false);
    Fixture otherTenant = fixture(1L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 2, "DEAD", false);
    when(engine.status(requested.commandId())).thenReturn(status(
        otherTenant.commandId(), "STOPPED", InstanceOperation.STOP,
        "STOPPED", "eng-other"));

    assertThatThrownBy(() -> controller.reconcile(requested.taskId()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("命令");
    assertThat(value("SELECT state FROM async_task WHERE id=?", otherTenant.taskId()))
        .isEqualTo("UNKNOWN");
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", otherTenant.instanceId()))
        .isEqualTo("UNKNOWN");
    assertThat(count("SELECT COUNT(*) FROM inbox_event")).isZero();
  }

  @Test
  void reconciliationRejectsAnEngineOperationDifferentFromTheRequestedTask() {
    Fixture fixture = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 2, "DEAD", false);
    when(engine.status(fixture.commandId())).thenReturn(status(
        fixture.commandId(), "RUNNING", InstanceOperation.START, "RUNNING", "eng-existing"));

    assertThatThrownBy(() -> controller.reconcile(fixture.taskId()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("操作");
    assertThat(value("SELECT state FROM async_task WHERE id=?", fixture.taskId()))
        .isEqualTo("UNKNOWN");
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("UNKNOWN");
    assertThat(count("SELECT COUNT(*) FROM inbox_event")).isZero();
  }

  private Fixture fixture(
      long tenantId,
      InstanceOperation operation,
      InstanceStatus previous,
      InstanceStatus current,
      InstanceStatus target,
      TaskState taskState,
      int retryCount,
      String outboxState,
      boolean expired) {
    long sequence = IDS.incrementAndGet();
    long orderId = IDS.incrementAndGet();
    long instanceId = IDS.incrementAndGet();
    long taskId = IDS.incrementAndGet();
    long actorId = tenantId == 1 ? 1 : 2;
    jdbc.update("""
        INSERT INTO compute_order(
          id,order_no,tenant_id,product_id,product_snapshot,quantity,
          amount_cent,status,created_by)
        VALUES(?,?,?,1,'{}',1,12800,'COMPLETED',?)
        """, orderId, "ORD-RECOVERY-" + sequence, tenantId, actorId);
    jdbc.update("""
        INSERT INTO compute_instance(
          id,instance_no,order_id,tenant_id,product_id,cluster_id,
          name,scenario,status,engine_instance_id)
        VALUES(?,?,?,?,1,1,?,'SUCCESS',?,?)
        """, instanceId, "INS-RECOVERY-" + sequence, orderId, tenantId,
        "recovery-" + sequence, current.name(), operation == InstanceOperation.CREATE
            ? null : "eng-existing-" + instanceId);
    String commandId = "CMD-RECOVERY-" + sequence;
    jdbc.update("""
        INSERT INTO async_task(
          id,task_no,command_id,tenant_id,instance_id,state,retry_count,next_retry_at,
          deadline_at,operation_type,previous_instance_status,target_instance_status,
          scenario,actor_id,message_id,accepted_at)
        VALUES(?,?,?,?,?,?,?,NOW(3),IF(?,DATE_SUB(NOW(3),INTERVAL 1 SECOND),NULL),
          ?,?,?,'SUCCESS',?, ?,NOW(3))
        """, taskId, "TASK-RECOVERY-" + sequence, commandId, tenantId, instanceId,
        taskState.name(), retryCount, expired, operation.name(), previous.name(),
        target.name(), actorId, "MSG-RECOVERY-" + sequence);
    jdbc.update("UPDATE compute_instance SET active_task_id=? WHERE id=?", taskId, instanceId);
    long outboxId = insertOutbox(instanceId, taskId, outboxState);
    return new Fixture(instanceId, taskId, outboxId, commandId);
  }

  private long insertTask(
      long instanceId,
      long tenantId,
      InstanceOperation operation,
      InstanceStatus previous,
      InstanceStatus target,
      TaskState state,
      int retryCount,
      boolean expired) {
    long sequence = IDS.incrementAndGet();
    long taskId = IDS.incrementAndGet();
    jdbc.update("""
        INSERT INTO async_task(
          id,task_no,command_id,tenant_id,instance_id,state,retry_count,next_retry_at,
          deadline_at,operation_type,previous_instance_status,target_instance_status,
          scenario,actor_id,message_id,accepted_at)
        VALUES(?,?,?,?,?,?,?,NOW(3),IF(?,DATE_SUB(NOW(3),INTERVAL 1 SECOND),NULL),
          ?,?,?,'SUCCESS',2,?,NOW(3))
        """, taskId, "TASK-HISTORY-" + sequence, "CMD-HISTORY-" + sequence,
        tenantId, instanceId, state.name(), retryCount, expired, operation.name(),
        previous.name(), target.name(), "MSG-HISTORY-" + sequence);
    return taskId;
  }

  private long insertOutbox(long instanceId, long taskId, String state) {
    long sequence = IDS.incrementAndGet();
    String commandId = value("SELECT command_id FROM async_task WHERE id=?", taskId);
    String messageId = value("SELECT message_id FROM async_task WHERE id=?", taskId);
    jdbc.update("""
        INSERT INTO outbox_event(
          event_id,aggregate_type,aggregate_id,event_type,payload,state,retry_count,
          next_retry_at,task_id,command_id,message_id)
        VALUES(?,'INSTANCE',?,'INSTANCE_COMMAND','{}',?,0,NOW(3),?,?,?)
        """, "EVENT-RECOVERY-" + sequence, instanceId, state, taskId, commandId, messageId);
    return jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_id=?", Long.class,
        "EVENT-RECOVERY-" + sequence);
  }

  private static CommandStatusReply status(
      String commandId,
      String status,
      InstanceOperation operation,
      String instanceState,
      String engineInstanceId) {
    return CommandStatusReply.newBuilder()
        .setCommandId(commandId)
        .setStatus(status)
        .setOperation(com.yeqimin.computehub.proto.InstanceOperation.valueOf(operation.name()))
        .setInstanceState(instanceState)
        .setEngineInstanceId(engineInstanceId)
        .build();
  }

  private static CommandStatusReply missingStatus(String commandId) {
    return CommandStatusReply.newBuilder()
        .setCommandId(commandId)
        .setStatus("NOT_FOUND")
        .setOperation(com.yeqimin.computehub.proto.InstanceOperation
            .INSTANCE_OPERATION_UNSPECIFIED)
        .build();
  }

  private static Stream<Arguments> operations() {
    return Stream.of(
        Arguments.of(InstanceOperation.CREATE, InstanceStatus.REQUESTED,
            InstanceStatus.CREATING, InstanceStatus.RUNNING),
        Arguments.of(InstanceOperation.START, InstanceStatus.STOPPED,
            InstanceStatus.STARTING, InstanceStatus.RUNNING),
        Arguments.of(InstanceOperation.STOP, InstanceStatus.RUNNING,
            InstanceStatus.STOPPING, InstanceStatus.STOPPED),
        Arguments.of(InstanceOperation.RESTART, InstanceStatus.RUNNING,
            InstanceStatus.RESTARTING, InstanceStatus.RUNNING),
        Arguments.of(InstanceOperation.DELETE, InstanceStatus.RUNNING,
            InstanceStatus.DELETING, InstanceStatus.DELETED));
  }

  private void authenticateTenant(long tenantId) {
    UserPrincipal principal = new UserPrincipal(
        tenantId == 1 ? 1L : 2L, tenantId, "tenant", Set.of("TENANT_ADMIN"),
        Set.of("instance:retry"));
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            principal, "", List.of(new SimpleGrantedAuthority("instance:retry"))));
  }

  private Map<String, Object> row(String sql, Object... arguments) {
    return jdbc.queryForMap(sql, arguments);
  }

  private String value(String sql, Object... arguments) {
    return jdbc.queryForObject(sql, String.class, arguments);
  }

  private long count(String sql, Object... arguments) {
    return jdbc.queryForObject(sql, Long.class, arguments);
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private record Fixture(long instanceId, long taskId, long outboxId, String commandId) {}
}
