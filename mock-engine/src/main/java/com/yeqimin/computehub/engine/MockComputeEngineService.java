package com.yeqimin.computehub.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeqimin.computehub.proto.*;
import io.grpc.stub.StreamObserver;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MockComputeEngineService extends ComputeEngineGrpc.ComputeEngineImplBase {
  private final JdbcTemplate jdbc;private final String secret;private final ObjectMapper json=new ObjectMapper();private final HttpClient http=HttpClient.newHttpClient();private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2);private final Set<String> scheduledCommands=ConcurrentHashMap.newKeySet();
  public MockComputeEngineService(JdbcTemplate jdbc,@Value("${engine.callback-secret}")String secret){this.jdbc=jdbc;this.secret=secret;}
  @Override public void createInstance(CreateInstanceCommand cmd,StreamObserver<CommandAccepted> observer){
    int inserted=jdbc.update("INSERT IGNORE INTO mock_engine_command(command_id,instance_no,scenario,status,callback_url) VALUES(?,?,?,?,?)",cmd.getCommandId(),cmd.getInstanceNo(),cmd.getScenario(),"ACCEPTED",cmd.getCallbackUrl());
    if(inserted==1)schedule(cmd);observer.onNext(CommandAccepted.newBuilder().setAccepted(true).setMessage(inserted==1?"accepted":"duplicate command accepted").build());observer.onCompleted();
  }
  @EventListener(ApplicationReadyEvent.class)
  public void recoverPersistedCommands(){
    for(Map<String,Object> row:jdbc.queryForList("SELECT command_id,instance_no,scenario,status,engine_instance_id,callback_url FROM mock_engine_command WHERE status IN ('ACCEPTED','RUNNING','FAILED')")){
      String status=text(row,"status"),scenario=text(row,"scenario");
      CreateInstanceCommand cmd=CreateInstanceCommand.newBuilder().setCommandId(text(row,"command_id")).setInstanceNo(text(row,"instance_no")).setScenario(scenario).setCallbackUrl(text(row,"callback_url")).build();
      switch(CommandRecoveryPolicy.action(status,scenario)){
        case RESCHEDULE -> schedule(cmd);
        case REDELIVER_CALLBACK -> callback(cmd,status,text(row,"engine_instance_id"));
        case NONE -> { }
      }
    }
  }
  private void schedule(CreateInstanceCommand cmd){
    if(!scheduledCommands.add(cmd.getCommandId()))return;
    if("TIMEOUT".equals(cmd.getScenario())){scheduler.schedule(()->{try{jdbc.update("UPDATE mock_engine_command SET status='RUNNING',engine_instance_id=? WHERE command_id=?","eng-"+shortId(),cmd.getCommandId());}finally{scheduledCommands.remove(cmd.getCommandId());}},12,TimeUnit.SECONDS);return;}
    String status="FAIL".equals(cmd.getScenario())?"FAILED":"RUNNING";String engineId="RUNNING".equals(status)?"eng-"+shortId():"";
    scheduler.schedule(()->{try{jdbc.update("UPDATE mock_engine_command SET status=?,engine_instance_id=? WHERE command_id=?",status,engineId,cmd.getCommandId());callback(cmd,status,engineId);if("DUPLICATE_CALLBACK".equals(cmd.getScenario()))scheduler.schedule(()->callback(cmd,status,engineId),350,TimeUnit.MILLISECONDS);}finally{scheduledCommands.remove(cmd.getCommandId());}},1200,TimeUnit.MILLISECONDS);
  }
  private void callback(CreateInstanceCommand cmd,String status,String engineId){
    try{Map<String,Object> event=new LinkedHashMap<>();event.put("eventId","ENGEVT-"+cmd.getCommandId()+"-"+status);event.put("commandId",cmd.getCommandId());event.put("status",status);event.put("engineInstanceId",engineId);event.put("message","RUNNING".equals(status)?"Mock Engine 创建成功":"Mock Engine 模拟资源不足");String body=json.writeValueAsString(event),ts=String.valueOf(Instant.now().getEpochSecond()),sig=sign(ts,body);var request=HttpRequest.newBuilder(URI.create(cmd.getCallbackUrl())).header("Content-Type","application/json").header("X-Engine-Timestamp",ts).header("X-Engine-Signature",sig).POST(HttpRequest.BodyPublishers.ofString(body)).build();http.sendAsync(request,HttpResponse.BodyHandlers.discarding());}catch(Exception ignored){}
  }
  @Override public void getCommandStatus(CommandStatusRequest request,StreamObserver<CommandStatusReply> observer){List<Map<String,Object>> rows=jdbc.queryForList("SELECT status,engine_instance_id FROM mock_engine_command WHERE command_id=?",request.getCommandId());if(rows.isEmpty()){observer.onNext(CommandStatusReply.newBuilder().setCommandId(request.getCommandId()).setStatus("NOT_FOUND").build());}else{Map<String,Object>r=rows.getFirst();observer.onNext(CommandStatusReply.newBuilder().setCommandId(request.getCommandId()).setStatus(String.valueOf(r.get("status"))).setEngineInstanceId(r.get("engine_instance_id")==null?"":String.valueOf(r.get("engine_instance_id"))).build());}observer.onCompleted();}
  @Override public void getClusterMetrics(MetricsRequest request,StreamObserver<ClusterMetricsReply> observer){long now=Instant.now().getEpochSecond();double wave=(Math.sin(now/8.0)+1)/2;var reply=ClusterMetricsReply.newBuilder().addMetrics(metric("SH-GPU-01",42+wave*28,38+wave*15,51+wave*12,now)).addMetrics(metric("BJ-GPU-01",58+wave*22,44+wave*18,63+wave*10,now)).build();observer.onNext(reply);observer.onCompleted();}
  private static ClusterMetric metric(String code,double gpu,double cpu,double memory,long ts){return ClusterMetric.newBuilder().setClusterCode(code).setGpuUtilization(gpu).setCpuUtilization(cpu).setMemoryUtilization(memory).setTimestamp(ts).build();}
  private String sign(String ts,String body)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal((ts+"."+body).getBytes(StandardCharsets.UTF_8)));}
  private static String shortId(){return UUID.randomUUID().toString().replace("-","").substring(0,12);}
  private static String text(Map<String,Object> row,String key){Object value=row.get(key);return value==null?"":String.valueOf(value);}
}
