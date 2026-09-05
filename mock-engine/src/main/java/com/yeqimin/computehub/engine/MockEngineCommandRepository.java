package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.proto.InstanceCommand;
import com.yeqimin.computehub.proto.InstanceOperation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MockEngineCommandRepository {
  private static final String COLUMNS = """
      command_id, operation_type, instance_no, tenant_id, scenario, status,
      engine_instance_id, callback_url, result_message, executed_at
      """;

  private final JdbcTemplate jdbc;

  public MockEngineCommandRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean insertIfAbsent(InstanceCommand command) {
    return jdbc.update("""
        INSERT IGNORE INTO mock_engine_command(
          command_id, operation_type, instance_no, tenant_id, scenario, status,
          engine_instance_id, callback_url)
        VALUES(?,?,?,?,?,?,?,?)
        """,
        command.getCommandId(), command.getOperation().name(), command.getInstanceNo(),
        command.getTenantId(), command.getScenario(), "ACCEPTED",
        emptyToNull(command.getEngineInstanceId()), command.getCallbackUrl()) == 1;
  }

  public void complete(
      String commandId, String status, String engineInstanceId, String resultMessage) {
    jdbc.update("""
        UPDATE mock_engine_command
        SET status=?, engine_instance_id=?, result_message=?, executed_at=CURRENT_TIMESTAMP(3)
        WHERE command_id=?
        """, status, emptyToNull(engineInstanceId), resultMessage, commandId);
  }

  public Optional<StoredCommand> find(String commandId) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM mock_engine_command WHERE command_id=?",
        (result, rowNum) -> map(result), commandId).stream().findFirst();
  }

  public List<StoredCommand> recoverable() {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM mock_engine_command "
            + "WHERE status IN ('ACCEPTED','RUNNING','STOPPED','DELETED','FAILED')",
        (result, rowNum) -> map(result));
  }

  private static StoredCommand map(java.sql.ResultSet result) throws java.sql.SQLException {
    Timestamp executedAt = result.getTimestamp("executed_at");
    long tenantId = result.getLong("tenant_id");
    if (result.wasNull()) tenantId = 0;
    return new StoredCommand(
        result.getString("command_id"),
        InstanceOperation.valueOf(result.getString("operation_type")),
        result.getString("instance_no"),
        tenantId,
        result.getString("scenario"),
        result.getString("status"),
        nullToEmpty(result.getString("engine_instance_id")),
        result.getString("callback_url"),
        nullToEmpty(result.getString("result_message")),
        executedAt == null ? null : executedAt.toInstant());
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  public record StoredCommand(
      String commandId,
      InstanceOperation operation,
      String instanceNo,
      long tenantId,
      String scenario,
      String status,
      String engineInstanceId,
      String callbackUrl,
      String resultMessage,
      Instant executedAt) {

    InstanceCommand toCommand() {
      return InstanceCommand.newBuilder()
          .setCommandId(commandId)
          .setOperation(operation)
          .setInstanceNo(instanceNo)
          .setTenantId(tenantId)
          .setScenario(scenario)
          .setEngineInstanceId(engineInstanceId)
          .setCallbackUrl(callbackUrl)
          .build();
    }
  }
}
