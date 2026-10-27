package com.credflow.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record Delivery(UUID id, UUID lease, String bank, String lenderId, JsonNode payload) {
}
