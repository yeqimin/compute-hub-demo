package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.engine.MockEngineCallbackClient.CallbackEvent;
import com.yeqimin.computehub.engine.MockEngineCommandRepository.StoredCommand;
import com.yeqimin.computehub.proto.CommandAccepted;
import com.yeqimin.computehub.proto.InstanceCommand;
import com.yeqimin.computehub.proto.InstanceOperation;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class MockEngineCommandExecutor {
  private final MockEngineCommandRepository repository;
  private final MockEngineCallbackClient callbackClient;
  private final ScheduledExecutorService scheduler;
  private final Duration executionDelay;
  private final Duration timeoutDelay;
  private final boolean ownsScheduler;
  private final Set<String> scheduledCommands = ConcurrentHashMap.newKeySet();

  public MockEngineCommandExecutor(
      MockEngineCommandRepository repository, MockEngineCallbackClient callbackClient) {
    this(repository, callbackClient, Executors.newScheduledThreadPool(2),
        Duration.ofMillis(1200), Duration.ofSeconds(12), true);
  }

  MockEngineCommandExecutor(
      MockEngineCommandRepository repository,
      MockEngineCallbackClient callbackClient,
      ScheduledExecutorService scheduler,
      Duration executionDelay,
      Duration timeoutDelay) {
    this(repository, callbackClient, scheduler, executionDelay, timeoutDelay, false);
  }

  private MockEngineCommandExecutor(
      MockEngineCommandRepository repository,
      MockEngineCallbackClient callbackClient,
      ScheduledExecutorService scheduler,
      Duration executionDelay,
      Duration timeoutDelay,
      boolean ownsScheduler) {
    this.repository = repository;
    this.callbackClient = callbackClient;
    this.scheduler = scheduler;
    this.executionDelay = executionDelay;
    this.timeoutDelay = timeoutDelay;
    this.ownsScheduler = ownsScheduler;
  }

  public CommandAccepted accept(InstanceCommand command) {
    successfulState(command.getOperation());
    boolean inserted = repository.insertIfAbsent(command);
    if (inserted) schedule(command);
    return CommandAccepted.newBuilder()
        .setAccepted(true)
        .setMessage(inserted ? "accepted" : "duplicate command accepted")
        .build();
  }

  public void recoverPersistedCommands() {
    for (StoredCommand stored : repository.recoverable()) {
      switch (CommandRecoveryPolicy.action(stored.status(), stored.scenario())) {
        case RESCHEDULE -> schedule(stored.toCommand());
        case REDELIVER_CALLBACK -> callbackClient.send(callback(stored));
        case NONE -> { }
      }
    }
  }

  private void schedule(InstanceCommand command) {
    if (!scheduledCommands.add(command.getCommandId())) return;
    Duration delay = "TIMEOUT".equals(command.getScenario()) ? timeoutDelay : executionDelay;
    scheduler.schedule(() -> execute(command), delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void execute(InstanceCommand command) {
    try {
      boolean failed = "FAIL".equals(command.getScenario());
      String status = failed ? "FAILED" : successfulState(command.getOperation());
      String result = failed ? "FAILED" : "SUCCEEDED";
      String engineId = engineInstanceId(command, failed);
      String message = failed
          ? "Mock Engine simulated resource shortage"
          : "Mock Engine lifecycle operation succeeded";
      repository.complete(command.getCommandId(), status, engineId, message);
      if (!"TIMEOUT".equals(command.getScenario())) {
        CallbackEvent event = callback(command, result, status, engineId, message);
        callbackClient.send(event);
        if ("DUPLICATE_CALLBACK".equals(command.getScenario())) {
          scheduler.schedule(() -> callbackClient.send(event), 350, TimeUnit.MILLISECONDS);
        }
      }
    } finally {
      scheduledCommands.remove(command.getCommandId());
    }
  }

  private CallbackEvent callback(StoredCommand stored) {
    String result = "FAILED".equals(stored.status()) ? "FAILED" : "SUCCEEDED";
    return callback(stored.toCommand(), result, stored.status(),
        stored.engineInstanceId(), stored.resultMessage());
  }

  private CallbackEvent callback(
      InstanceCommand command,
      String result,
      String instanceState,
      String engineId,
      String message) {
    return new CallbackEvent(
        "ENGEVT-" + command.getCommandId() + "-" + instanceState,
        command.getCommandId(), command.getOperation(), result, instanceState,
        instanceState, engineId, message, command.getCallbackUrl());
  }

  private static String engineInstanceId(InstanceCommand command, boolean failed) {
    if (failed) return command.getEngineInstanceId();
    if (!command.getEngineInstanceId().isBlank()) return command.getEngineInstanceId();
    return "eng-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static String successfulState(InstanceOperation operation) {
    return switch (operation) {
      case CREATE, START, RESTART -> "RUNNING";
      case STOP -> "STOPPED";
      case DELETE -> "DELETED";
      default -> throw new IllegalArgumentException("unsupported operation");
    };
  }

  @PreDestroy
  void shutdown() {
    if (ownsScheduler) scheduler.shutdown();
  }
}
