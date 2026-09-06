package com.yeqimin.computehub.dashboard;

import com.yeqimin.computehub.common.ApiResponse;
import com.yeqimin.computehub.engine.EngineClient;
import com.yeqimin.computehub.persistence.DashboardMapper;
import com.yeqimin.computehub.proto.ClusterMetric;
import com.yeqimin.computehub.proto.ClusterMetricsReply;
import com.yeqimin.computehub.security.UserPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardControllerTest {
  private final DashboardMapper mapper = mock(DashboardMapper.class);
  private final EngineClient engine = mock(EngineClient.class);
  private final DashboardController controller = new DashboardController(new DashboardService(mapper), engine);

  @AfterEach
  void clearAuthentication() { SecurityContextHolder.clearContext(); }

  @Test
  void tenantMetricsOnlyExposeUsedClustersAndSuppressSharedPhysicalUtilization() {
    authenticate(2L, "TENANT_ADMIN");
    when(mapper.topology(2L)).thenReturn(List.of(Map.of("clusterCode", "SH-GPU-01")));
    when(engine.metrics()).thenReturn(ClusterMetricsReply.newBuilder()
        .addMetrics(metric("SH-GPU-01", 71.4d))
        .addMetrics(metric("BJ-GPU-01", 52.6d)).build());

    ApiResponse<?> response = controller.metrics();

    @SuppressWarnings("unchecked") List<Map<String, Object>> metrics = (List<Map<String, Object>>) response.data();
    assertThat(metrics).containsExactly(Map.of("clusterCode", "SH-GPU-01", "usageScope", "TENANT_SHARED_METRICS_SUPPRESSED"));
  }

  @Test
  void platformMetricsRetainPhysicalUtilizationForEveryCluster() {
    authenticate(null, "PLATFORM_ADMIN");
    when(engine.metrics()).thenReturn(ClusterMetricsReply.newBuilder().addMetrics(metric("SH-GPU-01", 71.44d)).build());

    ApiResponse<?> response = controller.metrics();

    @SuppressWarnings("unchecked") List<Map<String, Object>> metrics = (List<Map<String, Object>>) response.data();
    assertThat(metrics).containsExactly(Map.of("clusterCode", "SH-GPU-01", "gpuUtilization", 71.4d,
        "cpuUtilization", 20.0d, "memoryUtilization", 30.0d, "timestamp", 123L, "usageScope", "PLATFORM_PHYSICAL"));
  }

  private static ClusterMetric metric(String clusterCode, double gpu) {
    return ClusterMetric.newBuilder().setClusterCode(clusterCode).setGpuUtilization(gpu)
        .setCpuUtilization(20d).setMemoryUtilization(30d).setTimestamp(123L).build();
  }

  private static void authenticate(Long tenantId, String role) {
    UserPrincipal user = new UserPrincipal(1L, tenantId, "dashboard", Set.of(role), Set.of("resource:read"));
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
  }
}
