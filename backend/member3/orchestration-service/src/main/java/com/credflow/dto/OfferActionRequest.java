package com.credflow.dto;

import jakarta.validation.constraints.*;

public record OfferActionRequest(@NotNull @PositiveOrZero Long expectedOfferVersion) {
}
