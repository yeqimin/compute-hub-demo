package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.proto.ClusterMetric;
import com.yeqimin.computehub.proto.ClusterMetricsReply;
import com.yeqimin.computehub.proto.CommandAccepted;
import com.yeqimin.computehub.proto.CommandStatusReply;
import com.yeqimin.computehub.proto.CommandStatusRequest;
import com.yeqimin.computehub.proto.ComputeEngineGrpc;
import com.yeqimin.computehub.proto.CreateInstanceCommand;
import com.yeqimin.computehub.proto.InstanceCommand;
import com.yeqimin.computehub.proto.InstanceOperation;
import com.yeqimin.computehub.proto.MetricsRequest;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class MockComputeEngineService extends ComputeEngineGrpc.ComputeEngineImplBase {
  private final MockEngineCommandExecutor executor;
  private final MockEngineCommandRepository repository;

  public MockComputeEngineService(
      MockEngineCommandExecutor executor, MockEngineCommandRepository repository) {
    this.executor = executor;
    this.repository = repository;
  }

  @Override
  public void createInstance(
      CreateInstanceCommand command, StreamObserver<CommandAccepted> observer) {
    InstanceCommand adapted = InstanceCommand.newBuilder()
        .setCommandId(command.getCommandId())
        .setOperation(InstanceOperation.CREATE)
        .setInstanceNo(command.getInstanceNo())
        .setTenantId(command.getTenantId())
        .setProductId(command.getProductId())
        .setClusterCode(command.getClusterCode())
        .setScenario(command.getScenario())
        .setCallbackUrl(command.getCallbackUrl())
        .build();
    respond(executor.accept(adapted), observer);
  }

  @Override
  public void executeInstance(
      InstanceCommand command, StreamObserver<CommandAccepted> observer) {
    respond(executor.accept(command), observer);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverPersistedCommands() {
    executor.recoverPersistedCommands();
  }

  @Override
  public void getCommandStatus(
      CommandStatusRequest request, StreamObserver<CommandStatusReply> observer) {
    CommandStatusReply reply = repository.find(request.getCommandId())
        .map(stored -> CommandStatusReply.newBuilder()
            .setCommandId(stored.commandId())
            .setStatus(stored.status())
            .setEngineInstanceId(stored.engineInstanceId())
            .setOperation(stored.operation())
            .setInstanceState(stored.status())
            .build())
        .orElseGet(() -> CommandStatusReply.newBuilder()
            .setCommandId(request.getCommandId())
            .setStatus("NOT_FOUND")
            .build());
    observer.onNext(reply);
    observer.onCompleted();
  }

  @Override
  public void getClusterMetrics(
      MetricsRequest request, StreamObserver<ClusterMetricsReply> observer) {
    long now = Instant.now().getEpochSecond();
    double wave = (Math.sin(now / 8.0) + 1) / 2;
    ClusterMetricsReply reply = ClusterMetricsReply.newBuilder()
        .addMetrics(metric("SH-GPU-01", 42 + wave * 28, 38 + wave * 15,
            51 + wave * 12, now))
        .addMetrics(metric("BJ-GPU-01", 58 + wave * 22, 44 + wave * 18,
            63 + wave * 10, now))
        .build();
    observer.onNext(reply);
    observer.onCompleted();
  }

  private static void respond(
      CommandAccepted accepted, StreamObserver<CommandAccepted> observer) {
    observer.onNext(accepted);
    observer.onCompleted();
  }

  private static ClusterMetric metric(
      String code, double gpu, double cpu, double memory, long timestamp) {
    return ClusterMetric.newBuilder()
        .setClusterCode(code)
        .setGpuUtilization(gpu)
        .setCpuUtilization(cpu)
        .setMemoryUtilization(memory)
        .setTimestamp(timestamp)
        .build();
  }
}
