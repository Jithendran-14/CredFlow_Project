package com.credflow.dto;

import java.time.Instant;

public record NbfcBReceipt(String caseId, String caseReference, String statusCode, Instant receivedTime) {
}
