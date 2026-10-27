package com.credflow.eligibility.api;

import jakarta.validation.constraints.*;

public record RoutingPriorityInput(
    @NotNull @Positive Integer expectedVersion,
    @NotNull @Min(1) @Max(99999) Integer routingPriority,
    @NotBlank @Size(max=450) String reason) { }
