package com.yeqimin.computehub.instance;

import com.yeqimin.computehub.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/instances")
public class InstanceController {
  private final InstanceService service;
  public InstanceController(InstanceService service){this.service=service;}
  @GetMapping public ApiResponse<?> list(@RequestParam(required=false)String status,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size){return ApiResponse.ok(service.list(status,page,size));}
  @GetMapping("/{id}") public ApiResponse<?> get(@PathVariable long id){return ApiResponse.ok(service.get(id));}
  @PostMapping @PreAuthorize("hasAuthority('instance:create')") public ApiResponse<?> create(@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody CreateInstanceRequest request){return ApiResponse.ok(service.create(key,request));}
}
