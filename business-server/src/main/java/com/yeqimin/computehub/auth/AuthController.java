package com.yeqimin.computehub.auth;

import com.yeqimin.computehub.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService service;
  public AuthController(AuthService service) { this.service=service; }
  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
  @PostMapping("/login") public ApiResponse<Map<String,Object>> login(@Valid @RequestBody LoginRequest request) { return ApiResponse.ok(service.login(request.username(), request.password())); }
  @GetMapping("/me") public ApiResponse<Map<String,Object>> me() { return ApiResponse.ok(service.me()); }
}
