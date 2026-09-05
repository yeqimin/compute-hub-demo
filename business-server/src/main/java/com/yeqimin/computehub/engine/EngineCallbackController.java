package com.yeqimin.computehub.engine;

import com.fasterxml.jackson.databind.*;
import com.yeqimin.computehub.common.*;
import java.time.Instant;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/internal/engine")
public class EngineCallbackController {
  private final CallbackSigner signer;private final SettlementService settlement;private final ObjectMapper json;
  public EngineCallbackController(CallbackSigner signer,SettlementService settlement,ObjectMapper json){this.signer=signer;this.settlement=settlement;this.json=json;}
  @PostMapping("/events") public ApiResponse<?> callback(@RequestHeader("X-Engine-Timestamp")String timestamp,@RequestHeader("X-Engine-Signature")String signature,@RequestBody String body){
    long ts;try{ts=Long.parseLong(timestamp);}catch(Exception e){throw BusinessException.badRequest("回调时间戳不合法");}if(Math.abs(Instant.now().getEpochSecond()-ts)>300)throw BusinessException.forbidden("回调已过期");if(!signer.verify(timestamp,body,signature))throw BusinessException.forbidden("回调签名错误");
    try{return ApiResponse.ok(settlement.settle(json.readValue(body,EngineEventRequest.class),RequestFingerprint.of(body)));}catch(BusinessException e){throw e;}catch(Exception e){throw BusinessException.badRequest("回调数据格式错误");}
  }
}
