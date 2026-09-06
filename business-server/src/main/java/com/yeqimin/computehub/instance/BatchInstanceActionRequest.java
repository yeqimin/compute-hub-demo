package com.yeqimin.computehub.instance;

import com.yeqimin.computehub.domain.EngineScenario;
import com.yeqimin.computehub.domain.InstanceOperation;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BatchInstanceActionRequest(
    @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> instanceIds,
    @NotNull InstanceOperation action,
    EngineScenario scenario) {
  public BatchInstanceActionRequest {
    instanceIds = instanceIds == null ? null : List.copyOf(instanceIds);
    scenario = scenario == null ? EngineScenario.SUCCESS : scenario;
  }
}
