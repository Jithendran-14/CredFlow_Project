package com.credflow.dto;

import java.time.Instant;

public record BankCReceipt(String bankApplicationId, String submissionReference, String processingState,
		Instant acceptedAt) {
}
