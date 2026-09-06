package com.yeqimin.computehub.domain;

public record TransitionPlan(
    InstanceStatus previous,
    InstanceStatus executing,
    InstanceStatus target,
    InstanceOperation operation) {}
