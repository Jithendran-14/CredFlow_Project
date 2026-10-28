package com.credflow.auth.history;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record BusinessHistoryInput(@Min(0) int expectedVersion, @NotNull Boolean businessDetailsReviewed,
		@NotNull @Size(min = 3, max = 3) List<@Valid Month> months,
		@Pattern(regexp = "POOR|FAIR|GOOD|EXCELLENT|NOT_AVAILABLE") String creditScoreBand) {

	public BusinessHistoryInput(int version, Boolean reviewed, List<Month> months) {
		this(version, reviewed, months, "NOT_AVAILABLE");
	}
	public record Month(@NotBlank @Pattern(regexp = "[0-9]{4}-[0-9]{2}") String month,
			@NotBlank @Pattern(regexp = "AVAILABLE|MISSING") String status, String grossSalesAmount,
			String returnsAmount, String payoutAmount, Long orderCount, Long returnedOrderCount) {
	}
}
