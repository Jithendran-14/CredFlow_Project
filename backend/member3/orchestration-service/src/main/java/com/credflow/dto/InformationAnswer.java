package com.credflow.dto;

import jakarta.validation.constraints.*;

public record InformationAnswer(@NotBlank String questionId, @NotBlank @Size(max = 4000) String answerText) {
}
