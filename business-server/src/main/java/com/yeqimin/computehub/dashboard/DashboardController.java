package com.yeqimin.computehub.dashboard;

import com.yeqimin.computehub.common.ApiResponse;
import com.yeqimin.computehub.engine.EngineClient;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class DashboardController {
  private final DashboardService dashboard;private final EngineClient engine;
  public DashboardController(DashboardService dashboard,EngineClient engine){this.dashboard=dashboard;this.engine=engine;}
  @GetMapping("/dashboard/summary") public ApiResponse<?> summary(){return ApiResponse.ok(dashboard.summary());}
  @GetMapping("/metrics/clusters") public ApiResponse<?> metrics(){
    try {
      DashboardService.MetricsScope scope = dashboard.metricsScope();
      var list = engine.metrics().getMetricsList().stream()
          .filter(metric -> scope.platformPhysical() || scope.clusterCodes().contains(metric.getClusterCode()))
          .map(metric -> metric(scope, metric)).toList();
      return ApiResponse.ok(list);
    } catch(Exception e) { return ApiResponse.ok(List.of()); }
  }
  private static Map<String,Object> metric(DashboardService.MetricsScope scope, com.yeqimin.computehub.proto.ClusterMetric metric) {
    if (!scope.platformPhysical()) return Map.of("clusterCode", metric.getClusterCode(), "usageScope", "TENANT_SHARED_METRICS_SUPPRESSED");
    return Map.of("clusterCode", metric.getClusterCode(), "gpuUtilization", round(metric.getGpuUtilization()), "cpuUtilization", round(metric.getCpuUtilization()), "memoryUtilization", round(metric.getMemoryUtilization()), "timestamp", metric.getTimestamp(), "usageScope", "PLATFORM_PHYSICAL");
  }
  private static double round(double v){return Math.round(v*10.0)/10.0;}
}
