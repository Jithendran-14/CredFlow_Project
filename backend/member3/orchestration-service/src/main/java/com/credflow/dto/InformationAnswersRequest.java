package com.credflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record InformationAnswersRequest(@NotNull @PositiveOrZero Long expectedInformationVersion,
		@NotEmpty List<@Valid InformationAnswer> answers, List<@NotBlank String> syntheticDocumentReferences) {

	public InformationAnswersRequest {
		syntheticDocumentReferences = syntheticDocumentReferences == null ? List.of()
				: List.copyOf(syntheticDocumentReferences);
	}
}
