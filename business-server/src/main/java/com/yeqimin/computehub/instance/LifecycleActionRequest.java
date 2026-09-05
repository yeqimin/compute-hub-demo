package com.yeqimin.computehub.instance;

import com.yeqimin.computehub.domain.EngineScenario;

public record LifecycleActionRequest(EngineScenario scenario) {
  public LifecycleActionRequest {
    scenario = scenario == null ? EngineScenario.SUCCESS : scenario;
  }
}
