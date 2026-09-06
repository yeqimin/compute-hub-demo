package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.proto.*;
import io.grpc.ManagedChannel;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EngineClient {
  private final ComputeEngineGrpc.ComputeEngineBlockingStub stub; private final String callbackUrl;
  public EngineClient(ManagedChannel channel,@Value("${compute-hub.callback-url}")String callbackUrl){this.stub=ComputeEngineGrpc.newBlockingStub(channel);this.callbackUrl=callbackUrl;}
  public CommandAccepted create(Map<String,Object> event){
    var cmd=CreateInstanceCommand.newBuilder().setCommandId(str(event,"commandId")).setInstanceNo(str(event,"instanceNo"))
        .setTenantId(num(event,"tenantId")).setProductId(num(event,"productId")).setClusterCode(str(event,"clusterCode"))
        .setScenario(str(event,"scenario")).setCallbackUrl(callbackUrl).build();
    return stub.withDeadlineAfter(2,TimeUnit.SECONDS).createInstance(cmd);
  }
  public CommandAccepted execute(Map<String,Object> event){
    var cmd=InstanceCommand.newBuilder().setCommandId(str(event,"commandId"))
        .setOperation(InstanceOperation.valueOf(str(event,"operation")))
        .setInstanceNo(str(event,"instanceNo")).setEngineInstanceId(optional(event,"engineInstanceId"))
        .setTenantId(num(event,"tenantId")).setProductId(num(event,"productId"))
        .setClusterCode(str(event,"clusterCode")).setScenario(str(event,"scenario"))
        .setCallbackUrl(callbackUrl).build();
    return stub.withDeadlineAfter(2,TimeUnit.SECONDS).executeInstance(cmd);
  }
  public CommandStatusReply status(String commandId){return stub.withDeadlineAfter(2,TimeUnit.SECONDS).getCommandStatus(CommandStatusRequest.newBuilder().setCommandId(commandId).build());}
  public ClusterMetricsReply metrics(){return stub.withDeadlineAfter(2,TimeUnit.SECONDS).getClusterMetrics(MetricsRequest.newBuilder().build());}
  private static String str(Map<String,Object> m,String k){return String.valueOf(m.get(k));}
  private static String optional(Map<String,Object> m,String k){Object value=m.get(k);return value==null?"":String.valueOf(value);}
  private static long num(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
}
