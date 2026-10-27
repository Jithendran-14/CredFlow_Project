package com.credflow.dto;

import java.util.UUID;

public record CallbackAcceptedResponse(UUID eventId, String status) {
}
