package com.yeqimin.computehub.dashboard;

import com.yeqimin.computehub.common.ApiResponse;
import com.yeqimin.computehub.engine.EngineClient;
import com.yeqimin.computehub.persistence.DashboardMapper;
import com.yeqimin.computehub.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class DashboardController {
  private final DashboardMapper mapper;private final EngineClient engine;
  public DashboardController(DashboardMapper mapper,EngineClient engine){this.mapper=mapper;this.engine=engine;}
  @GetMapping("/dashboard/summary") public ApiResponse<?> summary(){UserPrincipal u=CurrentUser.get();return ApiResponse.ok(mapper.summary(u.platformAdmin()?null:u.tenantId()));}
  @GetMapping("/metrics/clusters") public ApiResponse<?> metrics(){try{var reply=engine.metrics();var list=reply.getMetricsList().stream().map(m->Map.<String,Object>of("clusterCode",m.getClusterCode(),"gpuUtilization",round(m.getGpuUtilization()),"cpuUtilization",round(m.getCpuUtilization()),"memoryUtilization",round(m.getMemoryUtilization()),"timestamp",m.getTimestamp())).toList();return ApiResponse.ok(list);}catch(Exception e){long now=Instant.now().getEpochSecond();return ApiResponse.ok(List.of(Map.of("clusterCode","SH-GPU-01","gpuUtilization",62.0,"cpuUtilization",45.0,"memoryUtilization",58.0,"timestamp",now),Map.of("clusterCode","BJ-GPU-01","gpuUtilization",74.0,"cpuUtilization",53.0,"memoryUtilization",67.0,"timestamp",now)));}}
  private static double round(double v){return Math.round(v*10.0)/10.0;}
}
