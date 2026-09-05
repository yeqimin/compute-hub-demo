package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.common.BusinessException;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.TaskState;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SettlementServiceTest {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8")
      .withDatabaseName("test");

  private static final AtomicLong IDS = new AtomicLong(20_000);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }

  @MockitoBean OutboxWorker outboxWorker;
  @MockitoBean TaskTimeoutScheduler taskTimeoutScheduler;
  @Autowired SettlementService settlement;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void resetSettlementData() {
    jdbc.update("DELETE FROM realtime_event");
    jdbc.update("DELETE FROM operation_audit_log");
    jdbc.update("DELETE FROM inbox_event");
    jdbc.update("DELETE FROM outbox_event");
    jdbc.update("DELETE FROM async_task");
    jdbc.update("DELETE FROM compute_instance");
    jdbc.update("DELETE FROM compute_order");
    jdbc.update("DELETE FROM wallet_ledger");
    jdbc.update("UPDATE tenant_wallet SET available_cent=1000000, frozen_cent=0 WHERE tenant_id=2");
  }

  @Test
  void stopSuccessDoesNotTouchWalletAndClearsTheExactActiveTask() {
    Fixture fixture = fixture(InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.STOPPING, InstanceStatus.STOPPED, TaskState.WAITING_CALLBACK);

    SettlementResult result = settlement.settle(event(
        fixture, "event-stop-success", InstanceOperation.STOP,
        "SUCCEEDED", InstanceStatus.STOPPED), hash('a'));

    assertThat(result).isEqualTo(new SettlementResult(
        false, fixture.instanceId(), fixture.taskId(),
        InstanceStatus.STOPPED, TaskState.SUCCEEDED));
    assertThat(row("SELECT available_cent, frozen_cent FROM tenant_wallet WHERE tenant_id=2"))
        .containsEntry("available_cent", 1_000_000L)
        .containsEntry("frozen_cent", 0L);
    assertThat(count("SELECT COUNT(*) FROM wallet_ledger")).isZero();
    assertThat(row("SELECT status, active_task_id FROM compute_instance WHERE id=?",
        fixture.instanceId()))
        .containsEntry("status", "STOPPED")
        .containsEntry("active_task_id", null);
    assertThat(row("SELECT state, engine_event_id FROM async_task WHERE id=?", fixture.taskId()))
        .containsEntry("state", "SUCCEEDED")
        .containsEntry("engine_event_id", "event-stop-success");
    assertThat(count("SELECT COUNT(*) FROM operation_audit_log WHERE task_id=? AND result='SUCCEEDED'",
        fixture.taskId())).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM realtime_event WHERE aggregate_id=?",
        fixture.instanceId())).isEqualTo(1);
  }

  @Test
  void lateCreateSuccessDeductsFrozenAmountOnce() {
    jdbc.update("UPDATE tenant_wallet SET available_cent=987200, frozen_cent=12800 WHERE tenant_id=2");
    Fixture fixture = fixture(InstanceOperation.CREATE, InstanceStatus.REQUESTED,
        InstanceStatus.UNKNOWN, InstanceStatus.RUNNING, TaskState.UNKNOWN);
    EngineEventRequest event = event(fixture, "event-create-success", InstanceOperation.CREATE,
        "SUCCEEDED", InstanceStatus.RUNNING);

    SettlementResult first = settlement.settle(event, hash('b'));
    SettlementResult duplicate = settlement.settle(event, hash('b'));

    assertThat(first.duplicate()).isFalse();
    assertThat(duplicate).isEqualTo(new SettlementResult(
        true, fixture.instanceId(), fixture.taskId(),
        InstanceStatus.RUNNING, TaskState.SUCCEEDED));
    assertThat(row("SELECT available_cent, frozen_cent FROM tenant_wallet WHERE tenant_id=2"))
        .containsEntry("available_cent", 987_200L)
        .containsEntry("frozen_cent", 0L);
    assertThat(count("SELECT COUNT(*) FROM wallet_ledger WHERE biz_no=? AND type='DEDUCT'",
        fixture.orderNo())).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM inbox_event WHERE engine_event_id='event-create-success'"))
        .isEqualTo(1);
    assertThat(value("SELECT status FROM compute_order WHERE id=?", fixture.orderId()))
        .isEqualTo("COMPLETED");
  }

  @Test
  void lateCreateFailureUnfreezesFundsOnce() {
    jdbc.update("UPDATE tenant_wallet SET available_cent=987200, frozen_cent=12800 WHERE tenant_id=2");
    Fixture fixture = fixture(InstanceOperation.CREATE, InstanceStatus.REQUESTED,
        InstanceStatus.UNKNOWN, InstanceStatus.RUNNING, TaskState.UNKNOWN);
    EngineEventRequest event = event(fixture, "event-create-failure", InstanceOperation.CREATE,
        "FAILED", InstanceStatus.FAILED);

    settlement.settle(event, hash('c'));
    settlement.settle(event, hash('c'));

    assertThat(row("SELECT available_cent, frozen_cent FROM tenant_wallet WHERE tenant_id=2"))
        .containsEntry("available_cent", 1_000_000L)
        .containsEntry("frozen_cent", 0L);
    assertThat(count("SELECT COUNT(*) FROM wallet_ledger WHERE biz_no=? AND type='UNFREEZE'",
        fixture.orderNo())).isEqualTo(1);
    assertThat(value("SELECT status FROM compute_order WHERE id=?", fixture.orderId()))
        .isEqualTo("FAILED");
  }

  @Test
  void conflictingCallbackAfterTerminalResultWritesAuditOnly() {
    Fixture fixture = fixture(InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.STOPPING, InstanceStatus.STOPPED, TaskState.WAITING_CALLBACK);
    settlement.settle(event(fixture, "event-terminal-first", InstanceOperation.STOP,
        "SUCCEEDED", InstanceStatus.STOPPED), hash('d'));

    SettlementResult ignored = settlement.settle(event(
        fixture, "event-terminal-conflict", InstanceOperation.STOP,
        "FAILED", InstanceStatus.FAILED), hash('e'));

    assertThat(ignored).isEqualTo(new SettlementResult(
        true, fixture.instanceId(), fixture.taskId(),
        InstanceStatus.STOPPED, TaskState.SUCCEEDED));
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("STOPPED");
    assertThat(count("SELECT COUNT(*) FROM operation_audit_log WHERE task_id=?",
        fixture.taskId())).isEqualTo(2);
    assertThat(count("SELECT COUNT(*) FROM operation_audit_log WHERE task_id=? AND result='IGNORED_CONFLICT'",
        fixture.taskId())).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM realtime_event WHERE aggregate_id=?",
        fixture.instanceId())).isEqualTo(1);
  }

  @Test
  void callbackOperationMustMatchTheCommandOperation() {
    Fixture fixture = fixture(InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.STOPPING, InstanceStatus.STOPPED, TaskState.WAITING_CALLBACK);

    SettlementResult ignored = settlement.settle(event(
        fixture, "event-wrong-operation", InstanceOperation.START,
        "SUCCEEDED", InstanceStatus.RUNNING), hash('f'));

    assertThat(ignored.duplicate()).isTrue();
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("STOPPING");
    assertThat(value("SELECT state FROM async_task WHERE id=?", fixture.taskId()))
        .isEqualTo("WAITING_CALLBACK");
    assertThat(count("SELECT COUNT(*) FROM operation_audit_log WHERE task_id=? AND result='IGNORED_CONFLICT'",
        fixture.taskId())).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM realtime_event WHERE aggregate_id=?",
        fixture.instanceId())).isZero();
  }

  @Test
  void callbackCannotSettleACommandThatNoLongerOwnsTheInstance() {
    Fixture fixture = fixture(InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.STOPPING, InstanceStatus.STOPPED, TaskState.WAITING_CALLBACK);
    jdbc.update("UPDATE compute_instance SET active_task_id=NULL WHERE id=?", fixture.instanceId());

    SettlementResult ignored = settlement.settle(event(
        fixture, "event-not-active", InstanceOperation.STOP,
        "SUCCEEDED", InstanceStatus.STOPPED), hash('1'));

    assertThat(ignored.duplicate()).isTrue();
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("STOPPING");
    assertThat(value("SELECT state FROM async_task WHERE id=?", fixture.taskId()))
        .isEqualTo("WAITING_CALLBACK");
    assertThat(count("SELECT COUNT(*) FROM operation_audit_log WHERE task_id=? AND result='IGNORED_CONFLICT'",
        fixture.taskId())).isEqualTo(1);
  }

  @Test
  void reusedEngineEventIdWithDifferentPayloadIsRejected() {
    Fixture fixture = fixture(InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.STOPPING, InstanceStatus.STOPPED, TaskState.WAITING_CALLBACK);
    EngineEventRequest event = event(fixture, "event-reused", InstanceOperation.STOP,
        "SUCCEEDED", InstanceStatus.STOPPED);
    settlement.settle(event, hash('2'));

    assertThatThrownBy(() -> settlement.settle(event, hash('3')))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("engine_event_id");
    assertThat(count("SELECT COUNT(*) FROM inbox_event WHERE engine_event_id='event-reused'"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM operation_audit_log WHERE task_id=?",
        fixture.taskId())).isEqualTo(1);
  }

  @Test
  void createSuccessRequiresANonBlankEngineInstanceId() {
    jdbc.update("UPDATE tenant_wallet SET available_cent=987200, frozen_cent=12800 WHERE tenant_id=2");
    Fixture fixture = fixture(InstanceOperation.CREATE, InstanceStatus.REQUESTED,
        InstanceStatus.CREATING, InstanceStatus.RUNNING, TaskState.WAITING_CALLBACK);
    EngineEventRequest event = new EngineEventRequest(
        "event-create-without-engine-id", fixture.commandId(), InstanceOperation.CREATE,
        "SUCCEEDED", InstanceStatus.RUNNING, "", "engine result");

    assertThatThrownBy(() -> settlement.settle(event, hash('4')))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("engine_instance_id");
    assertThat(value("SELECT state FROM async_task WHERE id=?", fixture.taskId()))
        .isEqualTo("WAITING_CALLBACK");
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo("CREATING");
    assertThat(count("SELECT COUNT(*) FROM inbox_event WHERE engine_event_id=?",
        event.eventId())).isZero();
  }

  @Test
  void nonCreateCallbackCannotReplaceTheExistingEngineInstanceId() {
    Fixture fixture = fixture(InstanceOperation.STOP, InstanceStatus.RUNNING,
        InstanceStatus.STOPPING, InstanceStatus.STOPPED, TaskState.WAITING_CALLBACK);
    jdbc.update("UPDATE compute_instance SET engine_instance_id='eng-original' WHERE id=?",
        fixture.instanceId());
    EngineEventRequest event = new EngineEventRequest(
        "event-engine-id-conflict", fixture.commandId(), InstanceOperation.STOP,
        "SUCCEEDED", InstanceStatus.STOPPED, "eng-other", "engine result");

    assertThatThrownBy(() -> settlement.settle(event, hash('5')))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("engine_instance_id");
    assertThat(value("SELECT engine_instance_id FROM compute_instance WHERE id=?",
        fixture.instanceId())).isEqualTo("eng-original");
    assertThat(value("SELECT state FROM async_task WHERE id=?", fixture.taskId()))
        .isEqualTo("WAITING_CALLBACK");
    assertThat(count("SELECT COUNT(*) FROM inbox_event WHERE engine_event_id=?",
        event.eventId())).isZero();
  }

  @Test
  void nonCreateFailureMayOmitEngineInstanceIdWithoutClearingIt() {
    Fixture fixture = fixture(InstanceOperation.DELETE, InstanceStatus.RUNNING,
        InstanceStatus.DELETING, InstanceStatus.DELETED, TaskState.WAITING_CALLBACK);
    jdbc.update("UPDATE compute_instance SET engine_instance_id='eng-original' WHERE id=?",
        fixture.instanceId());
    EngineEventRequest event = new EngineEventRequest(
        "event-delete-failure-no-engine-id", fixture.commandId(), InstanceOperation.DELETE,
        "FAILED", InstanceStatus.DELETE_FAILED, "", "engine result");

    SettlementResult result = settlement.settle(event, hash('6'));

    assertThat(result.instanceStatus()).isEqualTo(InstanceStatus.DELETE_FAILED);
    assertThat(value("SELECT engine_instance_id FROM compute_instance WHERE id=?",
        fixture.instanceId())).isEqualTo("eng-original");
  }

  @ParameterizedTest
  @MethodSource("lifecycleFailures")
  void failedLifecycleCallbackUsesTheOperationSpecificFallback(
      InstanceOperation operation,
      InstanceStatus previous,
      InstanceStatus executing,
      InstanceStatus target,
      InstanceStatus fallback) {
    Fixture fixture = fixture(operation, previous, executing, target, TaskState.WAITING_CALLBACK);

    SettlementResult result = settlement.settle(event(
        fixture, "event-failure-" + operation, operation, "FAILED", InstanceStatus.FAILED),
        hash((char) ('g' + operation.ordinal())));

    assertThat(result.instanceStatus()).isEqualTo(fallback);
    assertThat(result.taskState()).isEqualTo(TaskState.FAILED);
    assertThat(value("SELECT status FROM compute_instance WHERE id=?", fixture.instanceId()))
        .isEqualTo(fallback.name());
  }

  private Fixture fixture(
      InstanceOperation operation,
      InstanceStatus previous,
      InstanceStatus current,
      InstanceStatus target,
      TaskState taskState) {
    long base = IDS.incrementAndGet();
    long orderId = base;
    long instanceId = base;
    long taskId = base;
    String orderNo = "ORD-SETTLE-" + base;
    String commandId = "CMD-SETTLE-" + base;
    String messageId = "MSG-SETTLE-" + base;
    jdbc.update("""
        INSERT INTO compute_order(
          id, order_no, tenant_id, product_id, product_snapshot, quantity,
          amount_cent, status, created_by)
        VALUES(?, ?, 2, 1, '{}', 1, 12800, 'FROZEN', 2)
        """, orderId, orderNo);
    jdbc.update("""
        INSERT INTO compute_instance(
          id, instance_no, order_id, tenant_id, product_id, cluster_id,
          name, scenario, status, active_task_id, engine_instance_id)
        VALUES(?, ?, ?, 2, 1, 1, ?, 'SUCCESS', ?, ?, ?)
        """, instanceId, "INS-SETTLE-" + base, orderId, "settlement-" + base,
        current.name(), taskId,
        operation == InstanceOperation.CREATE ? null : "eng-" + instanceId);
    jdbc.update("""
        INSERT INTO async_task(
          id, task_no, command_id, tenant_id, instance_id, state,
          next_retry_at, operation_type, previous_instance_status,
          target_instance_status, scenario, actor_id, message_id, accepted_at)
        VALUES(?, ?, ?, 2, ?, ?, NOW(3), ?, ?, ?, 'SUCCESS', 2, ?, NOW(3))
        """, taskId, "TASK-SETTLE-" + base, commandId, instanceId,
        taskState.name(), operation.name(), previous.name(), target.name(), messageId);
    jdbc.update("""
        INSERT INTO outbox_event(
          event_id, aggregate_type, aggregate_id, event_type, payload, state,
          retry_count, next_retry_at, task_id, command_id, message_id)
        VALUES(?, 'INSTANCE', ?, 'INSTANCE_COMMAND', '{}', 'SENT', 0,
          NOW(3), ?, ?, ?)
        """, messageId, instanceId, taskId, commandId, messageId);
    return new Fixture(orderId, orderNo, instanceId, taskId, commandId);
  }

  private EngineEventRequest event(
      Fixture fixture,
      String eventId,
      InstanceOperation operation,
      String result,
      InstanceStatus instanceStatus) {
    return new EngineEventRequest(
        eventId, fixture.commandId(), operation, result, instanceStatus,
        "eng-" + fixture.instanceId(), "engine result");
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

  private static String hash(char value) {
    return String.valueOf(value).repeat(64);
  }

  private static Stream<Arguments> lifecycleFailures() {
    return Stream.of(
        Arguments.of(InstanceOperation.START, InstanceStatus.STOPPED,
            InstanceStatus.STARTING, InstanceStatus.RUNNING, InstanceStatus.STOPPED),
        Arguments.of(InstanceOperation.STOP, InstanceStatus.RUNNING,
            InstanceStatus.STOPPING, InstanceStatus.STOPPED, InstanceStatus.RUNNING),
        Arguments.of(InstanceOperation.RESTART, InstanceStatus.RUNNING,
            InstanceStatus.RESTARTING, InstanceStatus.RUNNING, InstanceStatus.RUNNING),
        Arguments.of(InstanceOperation.DELETE, InstanceStatus.RUNNING,
            InstanceStatus.DELETING, InstanceStatus.DELETED, InstanceStatus.DELETE_FAILED));
  }

  private record Fixture(
      long orderId,
      String orderNo,
      long instanceId,
      long taskId,
      String commandId) {}
}
