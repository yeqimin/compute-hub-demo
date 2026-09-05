package com.yeqimin.computehub.engine;

import com.yeqimin.computehub.persistence.TaskMapper;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStatus;
import com.yeqimin.computehub.domain.RetryPolicy;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxWorker {
  private final TaskMapper tasks;private final EngineClient engine;
  public OutboxWorker(TaskMapper tasks,EngineClient engine){this.tasks=tasks;this.engine=engine;}
  @Scheduled(fixedDelay=500)
  @Transactional public void dispatch(){
    Map<String,Object> event=tasks.nextOutbox();if(event==null)return;
    long outbox=num(event,"id"),task=num(event,"taskId"),instance=num(event,"instanceId");int retry=((Number)event.get("taskRetryCount")).intValue();
    try{var accepted=engine.execute(event);if(!accepted.getAccepted())throw new IllegalStateException(accepted.getMessage());tasks.outboxWaiting(outbox);tasks.taskWaiting(task);tasks.restoreExecuting(instance,task,executing(event));}
    catch(Exception e){int next=retry+1;if(RetryPolicy.exhausted(next)){tasks.outboxFailure(outbox,"DEAD",0,shortMessage(e));tasks.taskFailure(task,"UNKNOWN",0,shortMessage(e));tasks.instanceUnknown(instance,task,executing(event));}else{int delay=RetryPolicy.delaySeconds(next);tasks.outboxFailure(outbox,"READY",delay,shortMessage(e));tasks.taskFailure(task,"READY",delay,shortMessage(e));}}
  }
  public void expireCallbacks(){TaskTimeoutScheduler.processExpiredCallbacks(tasks);}
  private static String executing(Map<String,Object> row){return executing(InstanceOperation.valueOf(String.valueOf(row.get("operation")))).name();}
  static InstanceStatus executing(InstanceOperation operation){return switch(operation){case CREATE->InstanceStatus.CREATING;case START->InstanceStatus.STARTING;case STOP->InstanceStatus.STOPPING;case RESTART->InstanceStatus.RESTARTING;case DELETE->InstanceStatus.DELETING;case RECONCILE->throw new IllegalArgumentException("RECONCILE is not dispatchable");};}
  private static long num(Map<String,Object> m,String k){return ((Number)m.get(k)).longValue();}
  private static String shortMessage(Exception e){String s=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();return s.substring(0,Math.min(480,s.length()));}
}
