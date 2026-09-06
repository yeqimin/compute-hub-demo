package com.yeqimin.computehub.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class LifecycleSchemaMigrationTest {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8").withDatabaseName("test");

  @Test
  void migrationBackfillsLegacyRowsWithExactOutboxTaskLinkage() throws Exception {
    migrateThroughV2();
    insertLegacyRows();
    migrateThroughV3();

    assertThat(columnExists("compute_instance", "active_task_id")).isTrue();
    assertThat(columnExists("async_task", "operation_type")).isTrue();
    assertThat(columnExists("outbox_event", "task_id")).isTrue();
    assertThat(columnExists("idempotency_record", "processing_token")).isTrue();
    assertThat(columnExists("idempotency_record", "locked_at")).isTrue();
    assertThat(tableExists("operation_audit_log")).isTrue();
    assertThat(tableExists("realtime_event")).isTrue();
    assertThat(tableExists("dead_letter_record")).isTrue();
    assertThat(columnIsNotNull("async_task", "operation_type")).isTrue();
    assertThat(columnIsNotNull("async_task", "previous_instance_status")).isTrue();
    assertThat(columnIsNotNull("async_task", "target_instance_status")).isTrue();
    assertThat(columnIsNotNull("async_task", "scenario")).isTrue();
    assertThat(columnIsNotNull("async_task", "actor_id")).isTrue();
    assertThat(columnIsNotNull("async_task", "message_id")).isTrue();
    assertThat(columnIsNotNull("outbox_event", "task_id")).isTrue();
    assertThat(columnIsNotNull("outbox_event", "command_id")).isTrue();
    assertThat(columnIsNotNull("outbox_event", "message_id")).isTrue();
    assertThat(columnIsNullable("idempotency_record", "processing_token")).isTrue();
    assertThat(columnIsNullable("idempotency_record", "locked_at")).isTrue();

    assertThat(value("SELECT state FROM async_task WHERE id = 101")).isEqualTo("PENDING");
    assertThat(value("SELECT state FROM async_task WHERE id = 102")).isEqualTo("SUCCEEDED");
    assertThat(value("SELECT message_id FROM async_task WHERE id = 101")).isEqualTo("event-pending");
    assertThat(value("SELECT message_id FROM async_task WHERE id = 102")).isEqualTo("event-succeeded");
    assertThat(value("SELECT task_id FROM outbox_event WHERE event_id = 'event-pending'")).isEqualTo("101");
    assertThat(value("SELECT task_id FROM outbox_event WHERE event_id = 'event-succeeded'")).isEqualTo("102");
    assertThat(value("SELECT command_id FROM outbox_event WHERE event_id = 'event-succeeded'"))
        .isEqualTo("command-succeeded");
    assertThat(value("SELECT state FROM outbox_event WHERE event_id = 'event-succeeded'"))
        .isEqualTo("SENT");
    assertThat(value("SELECT status FROM compute_instance WHERE id = 100")).isEqualTo("CREATING");
    assertThat(value("SELECT active_task_id FROM compute_instance WHERE id = 100")).isEqualTo("101");
    assertThat(value("SELECT processing_token FROM idempotency_record WHERE actor_id = 2"))
        .isNull();
    assertThat(value("SELECT locked_at FROM idempotency_record WHERE actor_id = 2"))
        .isNull();
  }

  private void migrateThroughV2() {
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .locations("classpath:db/migration")
        .target("2")
        .load()
        .migrate();
  }

  private void migrateThroughV3() {
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  private void insertLegacyRows() throws Exception {
    try (Connection connection = DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO compute_order(id, order_no, tenant_id, product_id, product_snapshot, quantity, amount_cent, status, created_by) "
          + "VALUES (100, 'ORDER-100', 2, 1, '{}', 1, 12800, 'PAID', 2)");
      statement.executeUpdate("INSERT INTO compute_instance(id, instance_no, order_id, tenant_id, product_id, cluster_id, name, scenario, status) "
          + "VALUES (100, 'INSTANCE-100', 100, 2, 1, 1, 'legacy-instance', 'SUCCESS', 'DISPATCHING')");
      statement.executeUpdate("INSERT INTO async_task(id, task_no, command_id, tenant_id, instance_id, state, next_retry_at) "
          + "VALUES (101, 'TASK-101', 'command-pending', 2, 100, 'READY', CURRENT_TIMESTAMP(3))");
      statement.executeUpdate("INSERT INTO async_task(id, task_no, command_id, tenant_id, instance_id, state, next_retry_at) "
          + "VALUES (102, 'TASK-102', 'command-succeeded', 2, 100, 'SUCCESS', CURRENT_TIMESTAMP(3))");
      statement.executeUpdate("INSERT INTO outbox_event(event_id, aggregate_type, aggregate_id, event_type, payload, state, next_retry_at) "
          + "VALUES ('event-pending', 'INSTANCE', 100, 'COMMAND', '{\"commandId\":\"command-pending\"}', 'WAITING_CALLBACK', CURRENT_TIMESTAMP(3))");
      statement.executeUpdate("INSERT INTO outbox_event(event_id, aggregate_type, aggregate_id, event_type, payload, state, next_retry_at) "
          + "VALUES ('event-succeeded', 'INSTANCE', 100, 'COMMAND', '{\"commandId\":\"command-succeeded\"}', 'WAITING_CALLBACK', CURRENT_TIMESTAMP(3))");
      statement.executeUpdate("INSERT INTO idempotency_record(actor_id, idempotency_key, request_hash, resource_type, status) "
          + "VALUES (2, 'legacy-idempotency', REPEAT('a', 64), 'INSTANCE_BATCH', 'PROCESSING')");
    }
  }

  private boolean columnExists(String table, String column) throws Exception {
    try (Connection connection = DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
      return columns.next();
    }
  }

  private boolean tableExists(String table) throws Exception {
    try (Connection connection = DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[] {"TABLE"})) {
      return tables.next();
    }
  }

  private boolean columnIsNotNull(String table, String column) throws Exception {
    try (Connection connection = DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
      return columns.next() && columns.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNoNulls;
    }
  }

  private boolean columnIsNullable(String table, String column) throws Exception {
    try (Connection connection = DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
      return columns.next() && columns.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable;
    }
  }

  private String value(String sql) throws Exception {
    try (Connection connection = DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }
}
