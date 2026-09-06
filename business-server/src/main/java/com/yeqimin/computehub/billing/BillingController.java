package com.yeqimin.computehub.billing;

import com.yeqimin.computehub.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class BillingController {
  private final BillingService service;
  public BillingController(BillingService service){this.service=service;}
  public record RechargeRequest(Long tenantId,@Positive long amountCent,String remark){}
  @GetMapping("/wallet") public ApiResponse<?> wallet(@RequestParam(required=false)Long tenantId){return ApiResponse.ok(service.wallet(tenantId));}
  @PostMapping("/wallet/recharges") @PreAuthorize("hasAuthority('billing:recharge')") public ApiResponse<?> recharge(@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody RechargeRequest r){return ApiResponse.ok(service.recharge(key,r.tenantId(),r.amountCent(),r.remark()));}
  @GetMapping("/billing/ledgers") public ApiResponse<?> ledgers(@RequestParam(required=false)Long tenantId,@RequestParam(required=false)String type,@RequestParam(required=false)String bizNo,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)LocalDateTime startTime,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)LocalDateTime endTime,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ApiResponse.ok(service.ledgers(tenantId,type,bizNo,startTime,endTime,page,size));}
}
