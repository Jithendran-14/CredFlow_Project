package com.credflow.dto;

import com.credflow.platform.Platform.Actor;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record RevisionGuardRequest(@NotNull UUID operationId, @Positive int expectedRevision, @Positive int newRevision, @NotNull Actor originActor) {
}
