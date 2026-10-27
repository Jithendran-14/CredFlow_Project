package com.credflow.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OwnershipCheckRequest(
        @NotBlank
        @Size(max = 100)
        String sellerId
) {
}