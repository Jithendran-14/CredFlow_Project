package com.credflow.application.dto;

public record OwnershipCheckResponse(String applicationId, boolean ownsApplication) {
}