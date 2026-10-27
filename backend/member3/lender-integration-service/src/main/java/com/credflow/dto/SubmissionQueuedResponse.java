package com.credflow.dto;

import java.util.UUID;

public record SubmissionQueuedResponse(UUID submissionId, UUID operationId, String status) {
}
