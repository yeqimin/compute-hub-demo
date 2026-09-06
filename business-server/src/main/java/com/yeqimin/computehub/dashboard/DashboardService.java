package com.yeqimin.computehub.dashboard;

import com.yeqimin.computehub.persistence.DashboardMapper;
import com.yeqimin.computehub.security.CurrentUser;
import com.yeqimin.computehub.security.UserPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
  private final DashboardMapper mapper;

  public DashboardService(DashboardMapper mapper) { this.mapper = mapper; }

  public Map<String,Object> summary() {
    UserPrincipal user = CurrentUser.get();
    Long tenantId = user.platformAdmin() ? null : user.tenantId();
    Map<String,Object> result = new LinkedHashMap<>(mapper.summary(tenantId));
    long gpuTotal = number(result, "gpuTotal");
    long gpuAllocated = number(result, "gpuAllocated");
    result.put("gpuAvailable", Math.max(0, gpuTotal - gpuAllocated));

    Map<String,Object> tasks = mapper.taskSummary(tenantId);
    long totalTasks = number(tasks, "totalTasks");
    result.put("taskSuccessRate", totalTasks == 0 ? 0.0d : round(number(tasks, "successfulTasks") * 100.0d / totalTasks));
    result.put("abnormalTasks", number(tasks, "abnormalTasks"));
    result.put("instanceDistribution", mapper.instanceDistribution(tenantId));
    result.put("resourceScope", user.platformAdmin() ? "PLATFORM_PHYSICAL" : "TENANT_LOGICAL_USAGE");
    result.put("topology", topology(mapper.topology(tenantId), user.platformAdmin()));
    return result;
  }

  public MetricsScope metricsScope() {
    UserPrincipal user = CurrentUser.get();
    if (user.platformAdmin()) return new MetricsScope(true, Set.of());
    Set<String> codes = new LinkedHashSet<>();
    for (Map<String, Object> row : mapper.topology(user.tenantId())) {
      Object code = row.get("clusterCode");
      if (code instanceof String value && !value.isBlank()) codes.add(value);
    }
    return new MetricsScope(false, Set.copyOf(codes));
  }

  public record MetricsScope(boolean platformPhysical, Set<String> clusterCodes) { }

  private static List<Map<String,Object>> topology(List<Map<String,Object>> rows, boolean platformPhysical) {
    Map<Long,Map<String,Object>> clusters = new LinkedHashMap<>();
    for (Map<String,Object> row : rows) {
      long id = number(row, "clusterId");
      Map<String,Object> cluster = clusters.computeIfAbsent(id, ignored -> {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("id", id); value.put("code", row.get("clusterCode")); value.put("name", row.get("clusterName"));
        value.put("region", row.get("clusterRegion")); value.put("status", row.get("clusterStatus"));
        value.put("nodes", new ArrayList<Map<String,Object>>()); return value;
      });
      @SuppressWarnings("unchecked") List<Map<String,Object>> nodes = (List<Map<String,Object>>) cluster.get("nodes");
      Map<String,Object> node = new LinkedHashMap<>();
      node.put("id", row.get("nodeId")); node.put("name", row.get("nodeName")); node.put("status", row.get("nodeStatus"));
      node.put("gpuModel", row.get("gpuModel")); node.put("gpuTotal", number(row, "gpuTotal"));
      node.put("gpuAllocated", platformPhysical ? number(row, "gpuAllocated") : null);
      nodes.add(node);
    }
    return new ArrayList<>(clusters.values());
  }

  private static long number(Map<String,Object> values, String key) {
    Object value = values.get(key); return value instanceof Number number ? number.longValue() : 0L;
  }

  private static double round(double value) { return Math.round(value * 10.0d) / 10.0d; }
}
