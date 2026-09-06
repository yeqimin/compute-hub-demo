package com.yeqimin.computehub.dashboard;

import com.yeqimin.computehub.persistence.DashboardMapper;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceTest {
  private final DashboardMapper mapper = mock(DashboardMapper.class);
  private final DashboardService service = new DashboardService(mapper);

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void tenantSummaryUsesTheAuthenticatedTenantForEveryBusinessMetric() {
    authenticate(2L, "TENANT_ADMIN");
    when(mapper.summary(2L)).thenReturn(Map.of("gpuTotal", 8L, "gpuAllocated", 3L));
    when(mapper.taskSummary(2L)).thenReturn(Map.of("successfulTasks", 3L, "abnormalTasks", 1L, "totalTasks", 4L));
    when(mapper.instanceDistribution(2L)).thenReturn(List.of(Map.of("status", "RUNNING", "value", 3L)));
    when(mapper.topology(2L)).thenReturn(List.of(Map.of("clusterId", 7L, "clusterCode", "SH-01", "clusterName", "上海", "clusterStatus", "READY", "nodeId", 8L, "nodeName", "node-a", "nodeStatus", "READY", "gpuTotal", 8L, "gpuAllocated", 3L)));

    Map<String, Object> result = service.summary();

    assertThat(result).containsEntry("gpuAvailable", 5L)
        .containsEntry("taskSuccessRate", 75.0d)
        .containsEntry("abnormalTasks", 1L)
        .containsEntry("instanceDistribution", List.of(Map.of("status", "RUNNING", "value", 3L)));
    assertThat((List<?>) result.get("topology")).hasSize(1);
    verify(mapper).summary(2L);
    verify(mapper).taskSummary(2L);
    verify(mapper).instanceDistribution(2L);
    verify(mapper).topology(2L);
  }

  @Test
  void platformSummaryUsesAllTenantScopeWithoutAcceptingARequestTenant() {
    authenticate(null, "PLATFORM_ADMIN");
    when(mapper.summary(null)).thenReturn(Map.of("gpuTotal", 10L, "gpuAllocated", 4L));
    when(mapper.taskSummary(null)).thenReturn(Map.of("successfulTasks", 0L, "abnormalTasks", 0L, "totalTasks", 0L));
    when(mapper.instanceDistribution(null)).thenReturn(List.of());
    when(mapper.topology(null)).thenReturn(List.of());

    Map<String, Object> result = service.summary();

    assertThat(result).containsEntry("gpuAvailable", 6L).containsEntry("taskSuccessRate", 0.0d);
    verify(mapper).summary(null);
    verify(mapper).taskSummary(null);
    verify(mapper).instanceDistribution(null);
    verify(mapper).topology(null);
  }

  private static void authenticate(Long tenantId, String role) {
    UserPrincipal user = new UserPrincipal(1L, tenantId, "dashboard", Set.of(role), Set.of("resource:read"));
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
  }
}
