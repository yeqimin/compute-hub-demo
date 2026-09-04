package com.yeqimin.computehub.instance;

import jakarta.validation.constraints.*;

public record CreateInstanceRequest(
    Long tenantId,
    @Positive long productId,
    @Positive long clusterId,
    @NotBlank @Size(max=128) String name,
    @Min(1) @Max(10) int quantity,
    @Pattern(regexp="SUCCESS|FAIL|DUPLICATE_CALLBACK|TIMEOUT") String scenario) {}
