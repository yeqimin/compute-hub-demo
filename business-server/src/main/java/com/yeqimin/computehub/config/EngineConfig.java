package com.yeqimin.computehub.config;

import com.yeqimin.computehub.engine.CallbackSigner;
import io.grpc.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class EngineConfig {
  private ManagedChannel channel;
  @Bean ManagedChannel engineChannel(@Value("${compute-hub.engine-host}")String host,@Value("${compute-hub.engine-port}")int port){channel=ManagedChannelBuilder.forAddress(host,port).usePlaintext().build();return channel;}
  @Bean CallbackSigner callbackSigner(@Value("${compute-hub.callback-secret}")String secret){return new CallbackSigner(secret);}
  @PreDestroy void close(){if(channel!=null)channel.shutdownNow();}
}
