package com.yeqimin.computehub.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LifecycleConcurrencyTest {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8")
      .withDatabaseName("test");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
      .withExposedPorts(6379);

  private static final AtomicLong SEQUENCE = new AtomicLong(10_000);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @LocalServerPort int port;

  private String tenantToken;
  private String viewerToken;

  @BeforeEach
  void login() {
    tenantToken = login("tenant_admin", "Tenant@123");
    viewerToken = login("viewer", "Viewer@123");
  }

  @Test
  void onlyOneConflictingOperationOwnsTheInstance() throws Exception {
    long instanceId = insertInstance(2, "RUNNING");

    List<Result> results = runTogether(List.of(
        () -> submit(tenantToken, instanceId, "stop", randomKey(), "SUCCESS"),
        () -> submit(tenantToken, instanceId, "restart", randomKey(), "SUCCESS")));

    assertThat(results).extracting(Result::httpStatus)
        .containsExactlyInAnyOrder(200, 409);
    assertThat(count("SELECT COUNT(*) FROM async_task WHERE instance_id = ?", instanceId))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM compute_instance WHERE id = ? AND active_task_id IS NOT NULL", instanceId))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM operation_audit_log WHERE instance_id = ? AND result = 'ACCEPTED'", instanceId))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM realtime_event WHERE aggregate_id = ?", instanceId))
        .isEqualTo(1);
  }

  @Test
  void fiftySameKeySubmissionsReturnOneOriginalTask() throws Exception {
    long instanceId = insertInstance(2, "RUNNING");
    String key = randomKey();
    List<Callable<Result>> submissions = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      submissions.add(() -> submit(tenantToken, instanceId, "stop", key, "SUCCESS"));
    }

    List<Result> results = runTogether(submissions);

    assertThat(results).extracting(Result::httpStatus).containsOnly(200);
    assertThat(results).extracting(Result::taskId).doesNotContainNull().containsOnly(
        results.getFirst().taskId());
    assertThat(count("SELECT COUNT(*) FROM async_task WHERE instance_id = ? AND operation_type = 'STOP'", instanceId))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?", instanceId))
        .isEqualTo(1);
  }

  @Test
  void sameKeyWithDifferentFingerprintReturnsConflict() {
    long instanceId = insertInstance(2, "RUNNING");
    String key = randomKey();

    Result first = submit(tenantToken, instanceId, "stop", key, "SUCCESS");
    Result conflict = submit(tenantToken, instanceId, "stop", key, "FAIL");

    assertThat(first.httpStatus()).isEqualTo(200);
    assertThat(conflict.httpStatus()).isEqualTo(409);
    assertThat(conflict.code()).isEqualTo("CONFLICT");
    assertThat(count("SELECT COUNT(*) FROM async_task WHERE instance_id = ?", instanceId))
        .isEqualTo(1);
  }

  @Test
  void tenantAdminCannotDiscoverAnotherTenantsInstance() {
    long otherTenantInstance = insertInstance(1, "RUNNING");

    Result result = submit(tenantToken, otherTenantInstance, "stop", randomKey(), "SUCCESS");

    assertThat(result.httpStatus()).isEqualTo(404);
    assertThat(result.code()).isEqualTo("NOT_FOUND");
    assertThat(count("SELECT COUNT(*) FROM async_task WHERE instance_id = ?", otherTenantInstance))
        .isZero();
  }

  @Test
  void viewerCannotSubmitLifecycleWrites() {
    long instanceId = insertInstance(2, "RUNNING");

    Result result = submit(viewerToken, instanceId, "stop", randomKey(), "SUCCESS");

    assertThat(result.httpStatus()).isEqualTo(403);
    assertThat(result.code()).isEqualTo("FORBIDDEN");
    assertThat(count("SELECT COUNT(*) FROM async_task WHERE instance_id = ?", instanceId))
        .isZero();
  }

  @Test
  void batchReturnsOnePartialResultPerRequestedInstance() {
    long running = insertInstance(2, "RUNNING");
    long stopped = insertInstance(2, "STOPPED");
    long otherTenant = insertInstance(1, "RUNNING");
    Map<String, Object> request = Map.of(
        "instanceIds", List.of(running, stopped, otherTenant),
        "action", "STOP",
        "scenario", "SUCCESS");

    Result result = exchange(tenantToken, HttpMethod.POST, "/api/v1/instances/batch-actions",
        randomKey(), request);

    assertThat(result.httpStatus()).isEqualTo(200);
    JsonNode data = result.body().path("data");
    assertThat(data.path("successCount").asInt()).isEqualTo(1);
    assertThat(data.path("skippedCount").asInt()).isEqualTo(1);
    assertThat(data.path("failedCount").asInt()).isEqualTo(1);
    assertThat(data.path("items")).hasSize(3);
    Set<Long> ids = new HashSet<>();
    data.path("items").forEach(item -> ids.add(item.path("instanceId").asLong()));
    assertThat(ids).containsExactlyInAnyOrder(running, stopped, otherTenant);
    assertThat(count("SELECT COUNT(*) FROM async_task WHERE instance_id IN (?, ?, ?)", running, stopped, otherTenant))
        .isEqualTo(1);
  }

  @Test
  void createKeepsWalletFreezeAndPopulatesLifecycleCorrelationFields() {
    long availableBefore = value("SELECT available_cent FROM tenant_wallet WHERE tenant_id = 2");
    long frozenBefore = value("SELECT frozen_cent FROM tenant_wallet WHERE tenant_id = 2");
    Map<String, Object> request = Map.of(
        "productId", 1,
        "clusterId", 1,
        "name", "create-compat-" + SEQUENCE.incrementAndGet(),
        "quantity", 1,
        "scenario", "SUCCESS");

    Result result = exchange(tenantToken, HttpMethod.POST, "/api/v1/instances",
        randomKey(), request);

    assertThat(result.httpStatus()).isEqualTo(200);
    long instanceId = result.body().path("data").path("id").asLong();
    Map<String, Object> row = jdbc.queryForMap("""
        SELECT i.status, i.active_task_id, t.id task_id, t.operation_type,
               t.previous_instance_status, t.target_instance_status, t.scenario,
               t.actor_id, t.message_id, t.state task_state,
               o.task_id outbox_task_id, o.command_id outbox_command_id,
               o.message_id outbox_message_id, o.state outbox_state,
               t.command_id task_command_id
        FROM compute_instance i
        JOIN async_task t ON t.id = i.active_task_id
        JOIN outbox_event o ON o.task_id = t.id
        WHERE i.id = ?
        """, instanceId);

    assertThat(row)
        .containsEntry("status", "CREATING")
        .containsEntry("operation_type", "CREATE")
        .containsEntry("previous_instance_status", "REQUESTED")
        .containsEntry("target_instance_status", "RUNNING")
        .containsEntry("scenario", "SUCCESS")
        .containsEntry("task_state", "PENDING")
        .containsEntry("outbox_state", "READY");
    assertThat(((Number) row.get("active_task_id")).longValue())
        .isEqualTo(((Number) row.get("task_id")).longValue())
        .isEqualTo(((Number) row.get("outbox_task_id")).longValue());
    assertThat(row.get("actor_id")).isEqualTo(2L);
    assertThat(row.get("message_id")).isEqualTo(row.get("outbox_message_id"));
    assertThat(row.get("task_command_id")).isEqualTo(row.get("outbox_command_id"));
    assertThat(value("SELECT available_cent FROM tenant_wallet WHERE tenant_id = 2"))
        .isEqualTo(availableBefore - 12_800);
    assertThat(value("SELECT frozen_cent FROM tenant_wallet WHERE tenant_id = 2"))
        .isEqualTo(frozenBefore + 12_800);
    assertThat(count("SELECT COUNT(*) FROM wallet_ledger l JOIN compute_order o ON o.order_no = l.biz_no "
        + "JOIN compute_instance i ON i.order_id = o.id WHERE i.id = ? AND l.type = 'FREEZE'", instanceId))
        .isEqualTo(1);
  }

  @Test
  void deletedRowsAreHiddenUnlessDeletedIsExplicitlyFiltered() {
    long deleted = insertInstance(2, "DELETED");
    long running = insertInstance(2, "RUNNING");

    Result defaultList = exchange(tenantToken, HttpMethod.GET, "/api/v1/instances?page=1&size=100",
        null, null);
    Result deletedList = exchange(tenantToken, HttpMethod.GET,
        "/api/v1/instances?status=DELETED&page=1&size=100", null, null);

    assertThat(defaultList.httpStatus()).isEqualTo(200);
    assertThat(instanceIds(defaultList.body())).contains(running).doesNotContain(deleted);
    assertThat(deletedList.httpStatus()).isEqualTo(200);
    assertThat(instanceIds(deletedList.body())).contains(deleted).doesNotContain(running);
  }

  @Test
  void submissionOnlyStagesTheCommandUntilRabbitPublishingExists() throws Exception {
    long instanceId = insertInstance(2, "RUNNING");

    Result result = submit(tenantToken, instanceId, "stop", randomKey(), "SUCCESS");
    Thread.sleep(3_000);

    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(jdbc.queryForObject(
        "SELECT state FROM async_task WHERE id = ?", String.class, result.taskId()))
        .isEqualTo("PENDING");
    assertThat(jdbc.queryForObject(
        "SELECT state FROM outbox_event WHERE task_id = ?", String.class, result.taskId()))
        .isEqualTo("READY");
  }

  private String login(String username, String password) {
    ResponseEntity<JsonNode> response = http.postForEntity(
        "/api/v1/auth/login", Map.of("username", username, "password", password), JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody().path("data").path("token").asText();
  }

  private Result submit(String token, long instanceId, String operation, String key, String scenario) {
    return exchange(token, HttpMethod.POST,
        "/api/v1/instances/" + instanceId + "/" + operation,
        key, Map.of("scenario", scenario));
  }

  private Result exchange(String token, HttpMethod method, String path, String key, Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (key != null) headers.set("Idempotency-Key", key);
    ResponseEntity<JsonNode> response = http.exchange(
        "http://localhost:" + port + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    JsonNode responseBody = response.getBody() == null ? json.createObjectNode() : response.getBody();
    Long taskId = responseBody.path("data").path("taskId").isNumber()
        ? responseBody.path("data").path("taskId").longValue() : null;
    return new Result(response.getStatusCode().value(), responseBody.path("code").asText(), taskId, responseBody);
  }

  private List<Result> runTogether(List<Callable<Result>> calls) throws Exception {
    CountDownLatch ready = new CountDownLatch(calls.size());
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
      List<Future<Result>> futures = new ArrayList<>();
      for (Callable<Result> call : calls) {
        futures.add(pool.submit(() -> {
          ready.countDown();
          assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
          return call.call();
        }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<Result> results = new ArrayList<>();
      for (Future<Result> future : futures) results.add(future.get(30, TimeUnit.SECONDS));
      return results;
    }
  }

  private long insertInstance(long tenantId, String status) {
    long sequence = SEQUENCE.incrementAndGet();
    String orderNo = "TEST-ORD-" + sequence;
    jdbc.update("""
        INSERT INTO compute_order(order_no, tenant_id, product_id, product_snapshot,
          quantity, amount_cent, status, created_by)
        VALUES (?, ?, 1, '{}', 1, 12800, 'COMPLETED', ?)
        """, orderNo, tenantId, tenantId == 1 ? 1 : 2);
    long orderId = value("SELECT id FROM compute_order WHERE order_no = ?", orderNo);
    String instanceNo = "TEST-INS-" + sequence;
    jdbc.update("""
        INSERT INTO compute_instance(instance_no, order_id, tenant_id, product_id,
          cluster_id, name, scenario, status, deleted_at)
        VALUES (?, ?, ?, 1, 1, ?, 'SUCCESS', ?, IF(? = 'DELETED', NOW(3), NULL))
        """, instanceNo, orderId, tenantId, "fixture-" + sequence, status, status);
    return value("SELECT id FROM compute_instance WHERE instance_no = ?", instanceNo);
  }

  private Set<Long> instanceIds(JsonNode response) {
    Set<Long> result = new HashSet<>();
    response.path("data").path("items").forEach(item -> result.add(item.path("id").asLong()));
    return result;
  }

  private long value(String sql, Object... args) {
    Long result = jdbc.queryForObject(sql, Long.class, args);
    assertThat(result).isNotNull();
    return result;
  }

  private long count(String sql, Object... args) {
    return value(sql, args);
  }

  private static String randomKey() {
    return "test-" + UUID.randomUUID();
  }

  private record Result(int httpStatus, String code, Long taskId, JsonNode body) {}
}
