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
  @GetMapping("/metrics/clusters") public ApiResponse<?> metrics(){try{var reply=engine.metrics();var list=reply.getMetricsList().stream().map(m->Map.<String,Object>of("clusterCode",m.getClusterCode(),"gpuUtilization",round(m.getGpuUtilization()),"cpuUtilization",round(m.getCpuUtilization()),"memoryUtilization",round(m.getMemoryUtilization()),"timestamp",m.getTimestamp())).toList();return ApiResponse.ok(list);}catch(Exception e){return ApiResponse.ok(List.of());}}
  private static double round(double v){return Math.round(v*10.0)/10.0;}
}
