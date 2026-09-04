package com.yeqimin.computehub.catalog;

import com.yeqimin.computehub.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class CatalogController {
  private final CatalogService service;
  public CatalogController(CatalogService service){this.service=service;}
  public record TenantRequest(@NotBlank String code,@NotBlank String name){}
  public record UserRequest(Long tenantId,@NotBlank String username,@NotBlank String displayName,@Size(min=8) String password,@Positive long roleId){}
  public record RolesRequest(@NotEmpty List<Long> roleIds){}
  public record ProductRequest(@NotBlank String sku,@NotBlank String name,@NotBlank String gpuModel,@Positive int gpuCount,@Positive int cpuCores,@Positive int memoryGb,@Positive long priceCent,Boolean enabled){
    Map<String,Object> map(){Map<String,Object> m=new HashMap<>();m.put("sku",sku);m.put("name",name);m.put("gpuModel",gpuModel);m.put("gpuCount",gpuCount);m.put("cpuCores",cpuCores);m.put("memoryGb",memoryGb);m.put("priceCent",priceCent);m.put("enabled",enabled==null||enabled);return m;}
  }

  @GetMapping("/tenants") @PreAuthorize("hasRole('PLATFORM_ADMIN')") public ApiResponse<?> tenants(){return ApiResponse.ok(service.tenants());}
  @PostMapping("/tenants") @PreAuthorize("hasRole('PLATFORM_ADMIN')") public ApiResponse<?> createTenant(@Valid @RequestBody TenantRequest r){return ApiResponse.ok(service.createTenant(r.code(),r.name()));}
  @GetMapping("/users") @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')") public ApiResponse<?> users(){return ApiResponse.ok(service.users());}
  @PostMapping("/users") @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')") public ApiResponse<?> createUser(@Valid @RequestBody UserRequest r){return ApiResponse.ok(service.createUser(r.tenantId(),r.username(),r.displayName(),r.password(),r.roleId()));}
  @PutMapping("/users/{id}/roles") @PreAuthorize("hasRole('PLATFORM_ADMIN')") public ApiResponse<?> roles(@PathVariable long id,@Valid @RequestBody RolesRequest r){service.assignRoles(id,r.roleIds());return ApiResponse.ok(Map.of("updated",true));}
  @GetMapping("/roles") @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN')") public ApiResponse<?> roles(){return ApiResponse.ok(service.roles());}

  @GetMapping("/products") public ApiResponse<?> products(){return ApiResponse.ok(service.products());}
  @PostMapping("/products") @PreAuthorize("hasAuthority('product:manage')") public ApiResponse<?> createProduct(@Valid @RequestBody ProductRequest r){return ApiResponse.ok(service.saveProduct(null,r.map()));}
  @PutMapping("/products/{id}") @PreAuthorize("hasAuthority('product:manage')") public ApiResponse<?> updateProduct(@PathVariable long id,@Valid @RequestBody ProductRequest r){return ApiResponse.ok(service.saveProduct(id,r.map()));}
  @GetMapping("/clusters") public ApiResponse<?> clusters(){return ApiResponse.ok(service.clusters());}
  @GetMapping("/clusters/{id}/nodes") public ApiResponse<?> nodes(@PathVariable long id){return ApiResponse.ok(service.nodes(id));}
}
