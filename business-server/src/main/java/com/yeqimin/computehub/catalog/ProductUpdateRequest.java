package com.yeqimin.computehub.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** SKU is intentionally absent: it is immutable after a product is created. */
public record ProductUpdateRequest(
    @NotBlank String name, @NotBlank String gpuModel, @Positive int gpuCount,
    @Positive int cpuCores, @Positive int memoryGb, @Positive long priceCent,
    boolean enabled) {}
