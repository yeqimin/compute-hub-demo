package com.yeqimin.computehub.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.yeqimin.computehub.proto.CommandAccepted;
import com.yeqimin.computehub.proto.CommandStatusReply;
import com.yeqimin.computehub.proto.CommandStatusRequest;
import com.yeqimin.computehub.proto.InstanceCommand;
import com.yeqimin.computehub.proto.InstanceOperation;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MockEngineCommandExecutorTest {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8")
      .withDatabaseName("test");

  private JdbcTemplate jdbc;
  private MockEngineCommandRepository repository;
  private MockEngineCallbackClient callbackClient;
  private ScheduledExecutorService scheduler;
  private MockEngineCommandExecutor executor;

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("DROP TABLE IF EXISTS mock_engine_command");
    jdbc.execute("""
        CREATE TABLE mock_engine_command (
          command_id VARCHAR(64) PRIMARY KEY,
          operation_type VARCHAR(32) NOT NULL,
          instance_no VARCHAR(64) NOT NULL,
          tenant_id BIGINT NULL,
          scenario VARCHAR(32) NOT NULL,
          status VARCHAR(32) NOT NULL,
          engine_instance_id VARCHAR(128),
          callback_url VARCHAR(500) NOT NULL,
          result_message VARCHAR(500),
          executed_at TIMESTAMP(3) NULL,
          created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
          updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
            ON UPDATE CURRENT_TIMESTAMP(3)
        )
        """);
    repository = new MockEngineCommandRepository(jdbc);
    callbackClient = mock(MockEngineCallbackClient.class);
    scheduler = Executors.newScheduledThreadPool(2);
    executor = new MockEngineCommandExecutor(
        repository, callbackClient, scheduler, Duration.ZERO, Duration.ofMillis(25));
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdownNow();
  }

  @ParameterizedTest
  @CsvSource({
      "CREATE,RUNNING",
      "START,RUNNING",
      "STOP,STOPPED",
      "RESTART,RUNNING",
      "DELETE,DELETED"
  })
  void acceptsEachLifecycleOperationAndPersistsItsFinalState(
      InstanceOperation operation, String expectedState) {
    InstanceCommand command = command(operation, "SUCCESS");

    CommandAccepted accepted = executor.accept(command);

    assertThat(accepted.getAccepted()).isTrue();
    await().untilAsserted(() -> {
      MockEngineCommandRepository.StoredCommand stored = repository.find(command.getCommandId())
          .orElseThrow();
      assertThat(stored.operation()).isEqualTo(operation);
      assertThat(stored.status()).isEqualTo(expectedState);
      assertThat(stored.engineInstanceId()).isNotBlank();
      assertThat(stored.executedAt()).isNotNull();
    });
    verify(callbackClient, timeout(2000)).send(any());
  }

  @Test
  void failPersistsFailureAndSendsFailedResult() {
    InstanceCommand command = command(InstanceOperation.STOP, "FAIL");

    executor.accept(command);

    await().untilAsserted(() -> assertThat(repository.find(command.getCommandId()).orElseThrow().status())
        .isEqualTo("FAILED"));
    MockEngineCallbackClient.CallbackEvent event = captureSingleCallback();
    assertThat(event.operation()).isEqualTo(InstanceOperation.STOP);
    assertThat(event.result()).isEqualTo("FAILED");
    assertThat(event.instanceState()).isEqualTo("FAILED");
  }

  @Test
  void duplicateCallbackUsesTheSameEngineEventIdTwice() {
    InstanceCommand command = command(InstanceOperation.RESTART, "DUPLICATE_CALLBACK");

    executor.accept(command);

    var captor = org.mockito.ArgumentCaptor.forClass(
        MockEngineCallbackClient.CallbackEvent.class);
    verify(callbackClient, timeout(2000).times(2)).send(captor.capture());
    List<MockEngineCallbackClient.CallbackEvent> events = captor.getAllValues();
    assertThat(events).hasSize(2);
    assertThat(events.get(0).eventId()).isEqualTo(events.get(1).eventId());
    assertThat(events.get(0).result()).isEqualTo("SUCCEEDED");
    assertThat(events.get(0).instanceState()).isEqualTo("RUNNING");
  }

  @Test
  void timeoutPersistsResultWithoutSendingCallback() {
    InstanceCommand command = command(InstanceOperation.DELETE, "TIMEOUT");

    executor.accept(command);

    await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
        assertThat(repository.find(command.getCommandId()).orElseThrow().status())
            .isEqualTo("DELETED"));
    verify(callbackClient, never()).send(any());
  }

  @Test
  void unknownCommandStatusEchoesCommandIdWithNotFoundAndUnspecifiedOperation() {
    String commandId = "CMD-MISSING-" + UUID.randomUUID();
    MockComputeEngineService service = new MockComputeEngineService(executor, repository);
    AtomicReference<CommandStatusReply> response = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicBoolean completed = new AtomicBoolean();

    service.getCommandStatus(
        CommandStatusRequest.newBuilder().setCommandId(commandId).build(),
        new StreamObserver<>() {
          @Override
          public void onNext(CommandStatusReply value) {
            response.set(value);
          }

          @Override
          public void onError(Throwable throwable) {
            failure.set(throwable);
          }

          @Override
          public void onCompleted() {
            completed.set(true);
          }
        });

    assertThat(failure.get()).isNull();
    assertThat(completed).isTrue();
    assertThat(response.get()).isNotNull();
    assertThat(response.get().getCommandId()).isEqualTo(commandId);
    assertThat(response.get().getStatus()).isEqualTo("NOT_FOUND");
    assertThat(response.get().getOperation())
        .isEqualTo(InstanceOperation.INSTANCE_OPERATION_UNSPECIFIED);
  }

  @Test
  void completedCommandDoesNotRedeliverCallbackAfterRepositoryReconstruction() {
    InstanceCommand command = command(InstanceOperation.CREATE, "SUCCESS");
    executor.accept(command);
    verify(callbackClient, timeout(2000)).send(any());

    MockEngineCommandRepository reconstructedRepository =
        new MockEngineCommandRepository(jdbc);
    MockEngineCallbackClient reconstructedCallback = mock(MockEngineCallbackClient.class);
    ScheduledExecutorService reconstructedScheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      MockEngineCommandExecutor reconstructedExecutor = new MockEngineCommandExecutor(
          reconstructedRepository, reconstructedCallback, reconstructedScheduler,
          Duration.ZERO, Duration.ofMillis(25));

      reconstructedExecutor.recoverPersistedCommands();

      assertThat(reconstructedRepository.find(command.getCommandId()).orElseThrow().operation())
          .isEqualTo(InstanceOperation.CREATE);
      assertThat(reconstructedRepository.find(command.getCommandId()).orElseThrow().status())
          .isEqualTo("RUNNING");
      await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
          verify(reconstructedCallback, never()).send(any()));
    } finally {
      reconstructedScheduler.shutdownNow();
    }
  }

  @Test
  void duplicateAcceptAfterRepositoryReconstructionExecutesOnlyOnce() {
    InstanceCommand command = command(InstanceOperation.CREATE, "SUCCESS");
    executor.accept(command);
    verify(callbackClient, timeout(2000)).send(any());

    MockEngineCommandRepository reconstructedRepository =
        new MockEngineCommandRepository(jdbc);
    ScheduledExecutorService reconstructedScheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      MockEngineCommandExecutor reconstructedExecutor = new MockEngineCommandExecutor(
          reconstructedRepository, callbackClient, reconstructedScheduler,
          Duration.ZERO, Duration.ofMillis(25));

      CommandAccepted duplicate = reconstructedExecutor.accept(command);

      assertThat(duplicate.getAccepted()).isTrue();
      assertThat(duplicate.getMessage()).isEqualTo("duplicate command accepted");
      assertThat(jdbc.queryForObject(
          "SELECT COUNT(*) FROM mock_engine_command WHERE command_id=?",
          Integer.class, command.getCommandId())).isEqualTo(1);
      await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
          verify(callbackClient, org.mockito.Mockito.times(1)).send(any()));
    } finally {
      reconstructedScheduler.shutdownNow();
    }
  }

  @Test
  void overlappingExecutorsMustWinOneDurableExecutionClaim() {
    InstanceCommand command = command(InstanceOperation.START, "SUCCESS");
    assertThat(repository.insertIfAbsent(command)).isTrue();
    MockEngineCallbackClient sharedCallback = mock(MockEngineCallbackClient.class);
    ScheduledExecutorService firstScheduler = Executors.newSingleThreadScheduledExecutor();
    ScheduledExecutorService secondScheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      MockEngineCommandExecutor first = new MockEngineCommandExecutor(
          new MockEngineCommandRepository(jdbc), sharedCallback, firstScheduler,
          Duration.ZERO, Duration.ofMillis(25));
      MockEngineCommandExecutor second = new MockEngineCommandExecutor(
          new MockEngineCommandRepository(jdbc), sharedCallback, secondScheduler,
          Duration.ZERO, Duration.ofMillis(25));

      first.recoverPersistedCommands();
      second.recoverPersistedCommands();

      await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
          verify(sharedCallback, org.mockito.Mockito.times(1)).send(any()));
      assertThat(repository.find(command.getCommandId()).orElseThrow().status())
          .isEqualTo("RUNNING");
    } finally {
      firstScheduler.shutdownNow();
      secondScheduler.shutdownNow();
    }
  }

  @Test
  void recoveryExecutesOnlyLeaseExpiredProcessingCommands() {
    InstanceCommand command = command(InstanceOperation.STOP, "SUCCESS");
    assertThat(repository.insertIfAbsent(command)).isTrue();
    jdbc.update("""
        UPDATE mock_engine_command
        SET status='PROCESSING', updated_at=CURRENT_TIMESTAMP(3) - INTERVAL 1 MINUTE
        WHERE command_id=?
        """, command.getCommandId());

    executor.recoverPersistedCommands();

    await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
        assertThat(repository.find(command.getCommandId()).orElseThrow().status())
            .isEqualTo("STOPPED"));
    verify(callbackClient, timeout(2000)).send(any());
  }

  private MockEngineCallbackClient.CallbackEvent captureSingleCallback() {
    var captor = org.mockito.ArgumentCaptor.forClass(
        MockEngineCallbackClient.CallbackEvent.class);
    verify(callbackClient, timeout(2000)).send(captor.capture());
    return captor.getValue();
  }

  private InstanceCommand command(InstanceOperation operation, String scenario) {
    String commandId = "cmd-" + UUID.randomUUID();
    return InstanceCommand.newBuilder()
        .setCommandId(commandId)
        .setOperation(operation)
        .setInstanceNo("INS-100")
        .setEngineInstanceId(operation == InstanceOperation.CREATE ? "" : "eng-existing")
        .setTenantId(7)
        .setProductId(11)
        .setClusterCode("SH-GPU-01")
        .setScenario(scenario)
        .setCallbackUrl("http://localhost/callback")
        .build();
  }
}
