package com.yeqimin.computehub.engine;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class GrpcServerLifecycle implements SmartLifecycle {
  private final MockComputeEngineService service;private final int port;private Server server;private volatile boolean running;
  public GrpcServerLifecycle(MockComputeEngineService service,@Value("${engine.grpc-port}")int port){this.service=service;this.port=port;}
  @Override public void start(){try{server=NettyServerBuilder.forPort(port).addService(service).build().start();running=true;}catch(Exception e){throw new IllegalStateException("Cannot start gRPC server",e);}}
  @Override public void stop(){if(server!=null){server.shutdown();try{server.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}running=false;}
  @Override public boolean isRunning(){return running;}
  @Override public int getPhase(){return Integer.MAX_VALUE;}
}
