package com.credflow.dto;

import java.time.Instant;

public record BankAReceipt(String bankApplicationId, String submissionReference, String processingState,
		Instant acceptedAt) {
}
