package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.common.BusinessException;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.TaskState;
import com.yeqimin.computehub.persistence.TaskMapper;
import com.yeqimin.computehub.proto.CommandAccepted;
import com.yeqimin.computehub.proto.CommandStatusReply;
import com.yeqimin.computehub.security.UserPrincipal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
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
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskRecoveryServiceTest {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8")
      .withDatabaseName("test");

  private static final AtomicLong IDS = new AtomicLong(90_000);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("compute-hub.task-timeout-scan-ms", () -> "3600000");
  }

  @MockitoBean OutboxWorker scheduledOutboxWorker;
  @MockitoBean EngineClient engine;
  @Autowired TaskRecoveryService service;
  @Autowired TaskTimeoutScheduler scheduler;
  @Autowired TaskMapper tasks;
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
    authenticate(2L, Set.of("TENANT_ADMIN"), Set.of("instance:retry"));
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void listAppliesEveryFilterAndScopesTenantAdminsToTheirTenant() {
    Fixture expected = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 2, "DEAD", false);
    fixture(1L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 2, "DEAD", false);
    fixture(2L, InstanceOperation.START, InstanceStatus.STOPPED,
        InstanceStatus.UNKNOWN, InstanceStatus.RUNNING, TaskState.UNKNOWN, 2, "DEAD", false);
    LocalDateTime before = jdbc.queryForObject(
        "SELECT DATE_SUB(created_at,INTERVAL 1 SECOND) FROM async_task WHERE id=?",
        LocalDateTime.class, expected.taskId());
    LocalDateTime after = jdbc.queryForObject(
        "SELECT DATE_ADD(created_at,INTERVAL 1 SECOND) FROM async_task WHERE id=?",
        LocalDateTime.class, expected.taskId());

    Map<String, Object> page = service.list(new TaskQuery(
        1L, InstanceOperation.STOP, TaskState.UNKNOWN, expected.commandId(),
        expected.instanceNo(), before, after, "createdAt", "asc", 0, 500));

    assertThat(page).containsEntry("total", 1L).containsEntry("page", 1).containsEntry("size", 100);
    List<Map<String, Object>> items = items(page);
    assertThat(items).singleElement().satisfies(item -> {
      assertThat(number(item, "id")).isEqualTo(expected.taskId());
      assertThat(item).containsEntry("commandId", expected.commandId())
          .containsEntry("instanceNo", expected.instanceNo())
          .containsEntry("operation", "STOP")
          .containsEntry("state", "UNKNOWN");
    });
  }

  @Test
  void platformAdminCanSelectAnotherTenant() {
    Fixture tenantOne = fixture(1L, InstanceOperation.RESTART, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.RUNNING, TaskState.UNKNOWN, 3, "DEAD", false);
    fixture(2L, InstanceOperation.RESTART, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.RUNNING, TaskState.UNKNOWN, 3, "DEAD", false);
    authenticate(null, Set.of("PLATFORM_ADMIN"), Set.of("instance:retry"));

    Map<String, Object> page = service.list(new TaskQuery(
        1L, null, null, null, null, null, null, "id", "desc", 1, 20));

    assertThat(items(page)).extracting(item -> number(item, "id"))
        .containsExactly(tenantOne.taskId());
  }

  @Test
  void detailReturnsTaskInstanceOutboxRetryErrorAndTimelineFields() {
    Fixture fixture = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 3, "DEAD", false);
    jdbc.update("UPDATE async_task SET manual_retry_count=2,last_error='callback timeout' WHERE id=?",
        fixture.taskId());

    Map<String, Object> detail = service.detail(fixture.taskId());

    assertThat(detail).containsEntry("id", fixture.taskId())
        .containsEntry("instanceNo", fixture.instanceNo())
        .containsEntry("outboxState", "DEAD")
        .containsEntry("retryCount", 3)
        .containsEntry("manualRetryCount", 2)
        .containsEntry("lastError", "callback timeout");
    assertThat(detail.get("timeline")).isInstanceOf(List.class);
    assertThat((List<?>) detail.get("timeline")).isNotEmpty();
  }

  @Test
  void detailHidesAnotherTenantsTask() {
    Fixture other = fixture(1L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 3, "DEAD", false);

    assertThatThrownBy(() -> service.detail(other.taskId()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("任务不存在");
  }

  @Test
  void viewerCanReadOwnTasksButCannotRetryOrReconcile() {
    Fixture fixture = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 3, "DEAD", false);
    authenticate(2L, Set.of("VIEWER"), Set.of("resource:read"));

    assertThat(service.detail(fixture.taskId())).containsEntry("id", fixture.taskId());
    assertThatThrownBy(() -> service.retry(fixture.taskId()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.reconcile(fixture.taskId()))
        .isInstanceOf(AccessDeniedException.class);
    verify(engine, never()).status(fixture.commandId());
  }

  @Test
  void retryReactivatesOnlyTheExactUnknownTaskAndReusesItsCommand() {
    Fixture target = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 3, "DEAD", false);
    long historicalTask = insertTask(target.instanceId(), 2L, InstanceOperation.RESTART,
        InstanceStatus.RUNNING, InstanceStatus.RUNNING, TaskState.UNKNOWN, 3);
    long historicalOutbox = insertOutbox(target.instanceId(), historicalTask, "DEAD");

    Map<String, Object> result = service.retry(target.taskId());

    assertThat(result).containsEntry("taskId", target.taskId())
        .containsEntry("commandId", target.commandId())
        .containsEntry("status", "RETRYING");
    assertThat(row("SELECT state,retry_count,manual_retry_count,command_id FROM async_task WHERE id=?",
        target.taskId())).containsEntry("state", "READY")
        .containsEntry("retry_count", 0)
        .containsEntry("manual_retry_count", 1)
        .containsEntry("command_id", target.commandId());
    assertThat(row("SELECT state,command_id FROM outbox_event WHERE id=?", target.outboxId()))
        .containsEntry("state", "READY").containsEntry("command_id", target.commandId());
    assertThat(value("SELECT state FROM async_task WHERE id=?", historicalTask)).isEqualTo("UNKNOWN");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", historicalOutbox)).isEqualTo("DEAD");
    verify(engine, never()).status(target.commandId());
  }

  @Test
  void retryRequeuesTheExactDatabaseOutboxForGenericLifecycleDispatch() {
    Fixture target = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 3, "DEAD", false);
    long historicalTask = insertTask(target.instanceId(), 2L, InstanceOperation.RESTART,
        InstanceStatus.RUNNING, InstanceStatus.RUNNING, TaskState.UNKNOWN, 3);
    long historicalOutbox = insertOutbox(target.instanceId(), historicalTask, "DEAD");
    when(engine.execute(any())).thenReturn(CommandAccepted.newBuilder().setAccepted(true).build());

    service.retry(target.taskId());
    new OutboxWorker(tasks, engine).dispatch();

    assertThat(row("SELECT state FROM async_task WHERE id=?", target.taskId()))
        .containsEntry("state", "WAITING_CALLBACK");
    assertThat(row("SELECT state,command_id FROM outbox_event WHERE id=?", target.outboxId()))
        .containsEntry("state", "WAITING_CALLBACK").containsEntry("command_id", target.commandId());
    assertThat(value("SELECT state FROM async_task WHERE id=?", historicalTask)).isEqualTo("UNKNOWN");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", historicalOutbox)).isEqualTo("DEAD");
    verify(engine).execute(any());
  }

  @Test
  void reconcileProcessingAndNotFoundFactsDoNotSettleOrRedispatch() {
    Fixture processing = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 3, "DEAD", false);
    Fixture missing = fixture(2L, InstanceOperation.RESTART, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.RUNNING, TaskState.UNKNOWN, 3, "DEAD", false);
    when(engine.status(processing.commandId())).thenReturn(status(
        processing.commandId(), "PROCESSING", InstanceOperation.STOP, "", ""));
    when(engine.status(missing.commandId())).thenReturn(missingStatus(missing.commandId()));

    assertThat(service.reconcile(processing.taskId()))
        .containsEntry("status", "PROCESSING").containsEntry("settled", false);
    assertThat(service.reconcile(missing.taskId()))
        .containsEntry("status", "NOT_FOUND").containsEntry("settled", false);
    assertThat(value("SELECT state FROM async_task WHERE id=?", processing.taskId()))
        .isEqualTo("UNKNOWN");
    assertThat(value("SELECT state FROM async_task WHERE id=?", missing.taskId()))
        .isEqualTo("UNKNOWN");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", processing.outboxId()))
        .isEqualTo("DEAD");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", missing.outboxId()))
        .isEqualTo("DEAD");
  }

  @Test
  void reconcileExplicitEngineFactSettlesTheOriginalTask() {
    Fixture fixture = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.UNKNOWN, InstanceStatus.STOPPED, TaskState.UNKNOWN, 3, "DEAD", false);
    when(engine.status(fixture.commandId())).thenReturn(status(
        fixture.commandId(), "STOPPED", InstanceOperation.STOP, "STOPPED",
        "eng-existing-" + fixture.instanceId()));

    Map<String, Object> result = service.reconcile(fixture.taskId());

    assertThat(result).containsEntry("taskId", fixture.taskId())
        .containsEntry("status", "STOPPED").containsEntry("settled", true);
    assertThat(value("SELECT state FROM async_task WHERE id=?", fixture.taskId()))
        .isEqualTo("SUCCEEDED");
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("STOPPED");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", fixture.outboxId()))
        .isEqualTo("DONE");
  }

  @Test
  void timeoutSchedulerUsesTwoFourEightSecondsThenMarksUnknown() {
    Fixture fixture = fixture(2L, InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.STOPPING, InstanceStatus.STOPPED, TaskState.WAITING_CALLBACK, 0,
        "WAITING_CALLBACK", true);

    assertDelayAfterScan(fixture, 1, 2);
    expireAgain(fixture);
    assertDelayAfterScan(fixture, 2, 4);
    expireAgain(fixture);
    assertDelayAfterScan(fixture, 3, 8);
    expireAgain(fixture);

    scheduler.scanExpiredCallbacks();

    assertThat(value("SELECT state FROM async_task WHERE id=?", fixture.taskId()))
        .isEqualTo("UNKNOWN");
    assertThat(value("SELECT state FROM outbox_event WHERE id=?", fixture.outboxId()))
        .isEqualTo("DEAD");
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("UNKNOWN");
  }

  private void assertDelayAfterScan(Fixture fixture, int retryCount, int seconds) {
    scheduler.scanExpiredCallbacks();
    Map<String, Object> task = row(
        "SELECT state,retry_count,TIMESTAMPDIFF(MICROSECOND,NOW(3),next_retry_at) delayMicros "
            + "FROM async_task WHERE id=?", fixture.taskId());
    assertThat(task).containsEntry("state", "READY").containsEntry("retry_count", retryCount);
    assertThat(number(task, "delayMicros")).isBetween((seconds * 1_000_000L) - 500_000L,
        seconds * 1_000_000L);
  }

  private void expireAgain(Fixture fixture) {
    jdbc.update("UPDATE async_task SET state='WAITING_CALLBACK',deadline_at=DATE_SUB(NOW(3),INTERVAL 1 SECOND) WHERE id=?",
        fixture.taskId());
    jdbc.update("UPDATE outbox_event SET state='WAITING_CALLBACK' WHERE id=?", fixture.outboxId());
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
    String instanceNo = "INS-TASK-CENTER-" + sequence;
    jdbc.update("""
        INSERT INTO compute_order(
          id,order_no,tenant_id,product_id,product_snapshot,quantity,
          amount_cent,status,created_by)
        VALUES(?,?,?,1,'{}',1,12800,'COMPLETED',?)
        """, orderId, "ORD-TASK-CENTER-" + sequence, tenantId, actorId);
    jdbc.update("""
        INSERT INTO compute_instance(
          id,instance_no,order_id,tenant_id,product_id,cluster_id,
          name,scenario,status,engine_instance_id)
        VALUES(?,?,?,?,1,1,?,'SUCCESS',?,?)
        """, instanceId, instanceNo, orderId, tenantId,
        "task-center-" + sequence, current.name(), operation == InstanceOperation.CREATE
            ? null : "eng-existing-" + instanceId);
    String commandId = "CMD-TASK-CENTER-" + sequence;
    jdbc.update("""
        INSERT INTO async_task(
          id,task_no,command_id,tenant_id,instance_id,state,retry_count,next_retry_at,
          deadline_at,operation_type,previous_instance_status,target_instance_status,
          scenario,actor_id,message_id,accepted_at)
        VALUES(?,?,?,?,?,?,?,NOW(3),IF(?,DATE_SUB(NOW(3),INTERVAL 1 SECOND),NULL),
          ?,?,?,'SUCCESS',?,?,NOW(3))
        """, taskId, "TASK-CENTER-" + sequence, commandId, tenantId, instanceId,
        taskState.name(), retryCount, expired, operation.name(), previous.name(), target.name(),
        actorId, "MSG-TASK-CENTER-" + sequence);
    jdbc.update("UPDATE compute_instance SET active_task_id=? WHERE id=?", taskId, instanceId);
    long outboxId = insertOutbox(instanceId, taskId, outboxState);
    return new Fixture(instanceId, taskId, outboxId, commandId, instanceNo);
  }

  private long insertTask(
      long instanceId,
      long tenantId,
      InstanceOperation operation,
      InstanceStatus previous,
      InstanceStatus target,
      TaskState state,
      int retryCount) {
    long sequence = IDS.incrementAndGet();
    long taskId = IDS.incrementAndGet();
    jdbc.update("""
        INSERT INTO async_task(
          id,task_no,command_id,tenant_id,instance_id,state,retry_count,next_retry_at,
          operation_type,previous_instance_status,target_instance_status,scenario,
          actor_id,message_id,accepted_at)
        VALUES(?,?,?,?,?,?,?,NOW(3),?,?,?,'SUCCESS',2,?,NOW(3))
        """, taskId, "TASK-HISTORY-" + sequence, "CMD-HISTORY-" + sequence,
        tenantId, instanceId, state.name(), retryCount, operation.name(), previous.name(),
        target.name(), "MSG-HISTORY-" + sequence);
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
        """, "EVENT-TASK-CENTER-" + sequence, instanceId, state, taskId, commandId, messageId);
    return jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_id=?", Long.class,
        "EVENT-TASK-CENTER-" + sequence);
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

  private void authenticate(Long tenantId, Set<String> roles, Set<String> permissions) {
    UserPrincipal principal = new UserPrincipal(
        roles.contains("PLATFORM_ADMIN") ? 1L : roles.contains("VIEWER") ? 3L : 2L,
        tenantId, "task-user", roles, permissions);
    List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
    roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
    permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(principal, "", authorities));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> page) {
    return (List<Map<String, Object>>) page.get("items");
  }

  private Map<String, Object> row(String sql, Object... arguments) {
    return jdbc.queryForMap(sql, arguments);
  }

  private String value(String sql, Object... arguments) {
    return jdbc.queryForObject(sql, String.class, arguments);
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }

  private record Fixture(
      long instanceId, long taskId, long outboxId, String commandId, String instanceNo) {}
}
