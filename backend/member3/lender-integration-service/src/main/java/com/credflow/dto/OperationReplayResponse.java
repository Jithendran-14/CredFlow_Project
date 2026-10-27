package com.credflow.dto;

import java.util.UUID;

public record OperationReplayResponse(UUID operationId, String status) {
}
