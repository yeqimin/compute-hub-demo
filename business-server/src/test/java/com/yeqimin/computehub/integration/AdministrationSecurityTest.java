package com.yeqimin.computehub.integration;

import com.yeqimin.computehub.engine.OutboxWorker;
import com.yeqimin.computehub.engine.TaskTimeoutScheduler;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdministrationSecurityTest {
  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8").withDatabaseName("administration");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
  }
  @MockitoBean OutboxWorker outboxWorker;
  @MockitoBean TaskTimeoutScheduler taskTimeoutScheduler;
  @Autowired TestRestTemplate http;
  @LocalServerPort int port;
  private String tenantToken;
  private String viewerToken;

  @BeforeEach void login() { tenantToken = login("tenant_admin", "Tenant@123"); viewerToken = login("viewer", "Viewer@123"); }

  @Test void productSearchIsServerPagedAndViewerCannotMutateAdministration() {
    ResponseEntity<Map> page = exchange("/api/v1/products?keyword=H800&page=1&size=1", HttpMethod.GET, tenantToken, null);
    assertThat(page.getStatusCode().value()).isEqualTo(200);
    assertThat((Map<?, ?>) page.getBody().get("data")).containsEntry("page", 1).containsEntry("size", 1).containsEntry("total", 1);
    assertThat(exchange("/api/v1/products", HttpMethod.POST, viewerToken, product()).getStatusCode().value()).isEqualTo(403);
    assertThat(exchange("/api/v1/users/2/roles", HttpMethod.PUT, viewerToken, Map.of("roleCodes", java.util.List.of("VIEWER"))).getStatusCode().value()).isEqualTo(403);
    assertThat(exchange("/api/v1/wallet/recharges", HttpMethod.POST, viewerToken, Map.of("amountCent", 100)).getStatusCode().value()).isEqualTo(403);
  }

  @Test void tenantAdminCanOnlyAssignRolesInsideOwnTenantAndCannotAssignPlatformRole() {
    assertThat(exchange("/api/v1/users/2/roles", HttpMethod.PUT, tenantToken, Map.of("roleCodes", java.util.List.of("VIEWER"))).getStatusCode().value()).isEqualTo(200);
    assertThat(exchange("/api/v1/users/1/roles", HttpMethod.PUT, tenantToken, Map.of("roleCodes", java.util.List.of("VIEWER"))).getStatusCode().value()).isEqualTo(403);
    assertThat(exchange("/api/v1/users/2/roles", HttpMethod.PUT, tenantToken, Map.of("roleCodes", java.util.List.of("PLATFORM_ADMIN"))).getStatusCode().value()).isEqualTo(403);
  }

  private String login(String username, String password) {
    ResponseEntity<Map> response = http.postForEntity(url("/api/v1/auth/login"), Map.of("username", username, "password", password), Map.class);
    return String.valueOf(((Map<?, ?>) response.getBody().get("data")).get("token"));
  }
  private ResponseEntity<Map> exchange(String path, HttpMethod method, String token, Object body) {
    HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON); if (path.contains("recharges")) headers.set("Idempotency-Key", "admin-test-key");
    return http.exchange(url(path), method, new HttpEntity<>(body, headers), Map.class);
  }
  private String url(String path) { return "http://localhost:" + port + path; }
  private Map<String, Object> product() { return Map.of("sku", "GPU-ADMIN-1", "name", "测试产品", "gpuModel", "A10", "gpuCount", 1, "cpuCores", 8, "memoryGb", 32, "priceCent", 100); }
}
