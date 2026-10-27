package com.credflow.dto;

import jakarta.validation.constraints.*;

public record WorkflowVersionRequest(@NotNull @PositiveOrZero Long expectedWorkflowVersion) {
}
