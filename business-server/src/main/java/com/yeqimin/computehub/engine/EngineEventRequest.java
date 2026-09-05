package com.yeqimin.computehub.engine;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.yeqimin.computehub.domain.InstanceOperation;
import com.yeqimin.computehub.domain.InstanceStatus;

public record EngineEventRequest(
    String eventId,
    String commandId,
    InstanceOperation operation,
    String result,
    @JsonAlias({"instanceState", "status"}) InstanceStatus instanceStatus,
    String engineInstanceId,
    String message) {}
