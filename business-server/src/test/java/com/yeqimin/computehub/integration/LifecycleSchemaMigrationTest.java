package com.yeqimin.computehub.integration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
  void migrationAddsLifecycleColumnsAndEventTables() throws Exception {
    migrateSchema();

    assertThat(columnExists("compute_instance", "active_task_id")).isTrue();
    assertThat(columnExists("async_task", "operation_type")).isTrue();
    assertThat(columnExists("outbox_event", "task_id")).isTrue();
    assertThat(tableExists("operation_audit_log")).isTrue();
    assertThat(tableExists("realtime_event")).isTrue();
    assertThat(tableExists("dead_letter_record")).isTrue();
  }

  private void migrateSchema() {
    Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
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
}
