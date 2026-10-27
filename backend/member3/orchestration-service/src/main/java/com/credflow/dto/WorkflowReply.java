package com.credflow.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;

public record WorkflowReply(int status, JsonNode body) {
}
