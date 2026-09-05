package com.yeqimin.computehub.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeqimin.computehub.common.BusinessException;
import com.yeqimin.computehub.common.RequestFingerprint;
import com.yeqimin.computehub.domain.EngineScenario;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.persistence.IdempotencyMapper;
import com.yeqimin.computehub.security.CurrentUser;
import com.yeqimin.computehub.security.UserPrincipal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class LifecycleCommandService {
  private static final Set<InstanceOperation> USER_OPERATIONS = Set.of(
      InstanceOperation.START,
      InstanceOperation.STOP,
      InstanceOperation.RESTART,
      InstanceOperation.DELETE);

  private final LifecycleCommandTxService tx;
  private final IdempotencyMapper idempotency;
  private final StringRedisTemplate redis;
  private final ObjectMapper json;

  public LifecycleCommandService(
      LifecycleCommandTxService tx,
      IdempotencyMapper idempotency,
      StringRedisTemplate redis,
      ObjectMapper json) {
    this.tx = tx;
    this.idempotency = idempotency;
    this.redis = redis;
    this.json = json;
  }

  public LifecycleSubmission submit(
      String key,
      long instanceId,
      InstanceOperation operation,
      EngineScenario scenario) {
    requireKey(key);
    requireUserOperation(operation);
    EngineScenario effectiveScenario = scenario == null ? EngineScenario.SUCCESS : scenario;
    UserPrincipal user = CurrentUser.get();
    String hash = fingerprint(Map.of(
        "kind", "INSTANCE_LIFECYCLE",
        "instanceId", instanceId,
        "operation", operation.name(),
        "scenario", effectiveScenario.name()));
    return submitGuarded(user, key, hash, instanceId, operation, effectiveScenario);
  }

  public BatchInstanceActionResponse submitBatch(String key, BatchInstanceActionRequest request) {
    requireKey(key);
    requireUserOperation(request.action());
    UserPrincipal user = CurrentUser.get();
    String hash = fingerprint(Map.of(
        "kind", "INSTANCE_BATCH",
        "instanceIds", request.instanceIds(),
        "operation", request.action().name(),
        "scenario", request.scenario().name()));
    String token = UUID.randomUUID().toString();
    String lock = lockKey(user.id(), key);
    boolean acquired = acquire(lock, token);
    try {
      if (!acquired) {
        BatchInstanceActionResponse replay = awaitReplay(
            user.id(), key, hash, BatchInstanceActionResponse.class);
        if (replay != null) return replay;
      }
      if (idempotency.tryStart(user.id(), key, hash, "INSTANCE_BATCH") == 0) {
        BatchInstanceActionResponse replay = completedReplay(
            user.id(), key, hash, BatchInstanceActionResponse.class);
        if (replay != null) return replay;
      }
      BatchInstanceActionResponse response = submitBatchItems(user, key, request);
      idempotency.complete(user.id(), key, 0, write(response));
      return response;
    } finally {
      release(lock, token, acquired);
    }
  }

  private BatchInstanceActionResponse submitBatchItems(
      UserPrincipal user,
      String batchKey,
      BatchInstanceActionRequest request) {
    List<BatchInstanceActionResponse.Item> items = new ArrayList<>();
    int success = 0;
    int skipped = 0;
    int failed = 0;
    for (long instanceId : request.instanceIds()) {
      String childHash = fingerprint(Map.of(
          "kind", "INSTANCE_BATCH_ITEM",
          "batchKey", batchKey,
          "instanceId", instanceId,
          "operation", request.action().name(),
          "scenario", request.scenario().name()));
      String childKey = "batch-item:" + childHash;
      try {
        LifecycleSubmission submission = tx.submit(
            user.id(),
            user.platformAdmin() ? null : user.tenantId(),
            instanceId,
            request.action(),
            request.scenario(),
            childKey,
            childHash);
        items.add(new BatchInstanceActionResponse.Item(
            instanceId, "SUCCESS", "已提交", submission.taskId()));
        success++;
      } catch (BusinessException e) {
        items.add(new BatchInstanceActionResponse.Item(instanceId, e.code(), e.getMessage(), null));
        if (e.status() == HttpStatus.CONFLICT) skipped++;
        else failed++;
      } catch (Exception e) {
        items.add(new BatchInstanceActionResponse.Item(
            instanceId, "INTERNAL_ERROR", shortMessage(e), null));
        failed++;
      }
    }
    return new BatchInstanceActionResponse(success, skipped, failed, items);
  }

  private LifecycleSubmission submitGuarded(
      UserPrincipal user,
      String key,
      String hash,
      long instanceId,
      InstanceOperation operation,
      EngineScenario scenario) {
    String token = UUID.randomUUID().toString();
    String lock = lockKey(user.id(), key);
    boolean acquired = acquire(lock, token);
    try {
      if (!acquired) {
        LifecycleSubmission replay = awaitReplay(user.id(), key, hash, LifecycleSubmission.class);
        if (replay != null) return replay;
      }
      return tx.submit(
          user.id(),
          user.platformAdmin() ? null : user.tenantId(),
          instanceId,
          operation,
          scenario,
          key,
          hash);
    } finally {
      release(lock, token, acquired);
    }
  }

  private <T> T awaitReplay(long actorId, String key, String hash, Class<T> type) {
    for (int i = 0; i < 100; i++) {
      T replay = completedReplay(actorId, key, hash, type);
      if (replay != null) return replay;
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    return null;
  }

  private <T> T completedReplay(long actorId, String key, String hash, Class<T> type) {
    Map<String, Object> old = idempotency.find(actorId, key);
    if (old == null) return null;
    if (!hash.equals(old.get("requestHash"))) {
      throw BusinessException.conflict("Idempotency-Key 已用于不同请求");
    }
    if (!"COMPLETED".equals(old.get("status"))) return null;
    try {
      return json.readValue(String.valueOf(old.get("responseBody")), type);
    } catch (Exception e) {
      throw new IllegalStateException("cannot deserialize idempotent replay", e);
    }
  }

  private boolean acquire(String lock, String token) {
    try {
      return Boolean.TRUE.equals(
          redis.opsForValue().setIfAbsent(lock, token, Duration.ofSeconds(10)));
    } catch (Exception ignored) {
      return true;
    }
  }

  private void release(String lock, String token, boolean acquired) {
    if (!acquired) return;
    try {
      if (token.equals(redis.opsForValue().get(lock))) redis.delete(lock);
    } catch (Exception ignored) {
      // MySQL remains authoritative if Redis is unavailable.
    }
  }

  private static void requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw BusinessException.badRequest("缺少 Idempotency-Key 请求头");
    }
    if (key.length() > 128) {
      throw BusinessException.badRequest("Idempotency-Key 长度不能超过 128");
    }
  }

  private static void requireUserOperation(InstanceOperation operation) {
    if (operation == null || !USER_OPERATIONS.contains(operation)) {
      throw BusinessException.badRequest("不支持的实例操作");
    }
  }

  private static String lockKey(long actorId, String key) {
    return "idem:" + actorId + ":" + key;
  }

  private String fingerprint(Object value) {
    return RequestFingerprint.of(write(value));
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("cannot serialize idempotency payload", e);
    }
  }

  private static String shortMessage(Exception e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    return message.substring(0, Math.min(500, message.length()));
  }
}
