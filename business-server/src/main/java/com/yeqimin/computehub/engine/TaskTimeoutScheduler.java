package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.common.BusinessException;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.RetryPolicy;
import com.yeqimin.computehub.persistence.TaskMapper;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TaskTimeoutScheduler {
  private final TaskMapper tasks;

  public TaskTimeoutScheduler(TaskMapper tasks) {
    this.tasks = tasks;
  }

  @Scheduled(
      initialDelayString = "${compute-hub.task-timeout-scan-ms:1000}",
      fixedDelayString = "${compute-hub.task-timeout-scan-ms:1000}")
  @Transactional
  public void scanExpiredCallbacks() {
    processExpiredCallbacks(tasks);
  }

  static void processExpiredCallbacks(TaskMapper tasks) {
    for (Map<String, Object> row : tasks.claimExpiredCallbacks(20)) {
      int retryCount = ((Number) row.get("retryCount")).intValue();
      long taskId = number(row, "taskId");
      long outboxId = number(row, "outboxId");
      long instanceId = number(row, "instanceId");
      String executing = OutboxWorker.executing(
          InstanceOperation.valueOf(String.valueOf(row.get("operation")))).name();
      if (retryCount >= 3) {
        if (tasks.taskUnknown(taskId) != 1
            || tasks.outboxDead(outboxId) != 1
            || tasks.instanceUnknown(instanceId, taskId, executing) != 1) {
          throw BusinessException.conflict("超时任务状态已变更");
        }
      } else {
        int delay = RetryPolicy.delaySeconds(retryCount + 1);
        if (tasks.retryExpired(taskId, delay) != 1
            || tasks.retryOutbox(outboxId, delay) != 1) {
          throw BusinessException.conflict("超时任务状态已变更");
        }
      }
    }
  }

  private static long number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).longValue();
  }
}
