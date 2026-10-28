package com.credflow.simulator.verification.api;

import com.credflow.simulator.verification.VerificationSimulator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/verification/checks")
public class VerificationController {
	private final VerificationSimulator simulator;

	public VerificationController(VerificationSimulator simulator) {
		this.simulator = simulator;
	}

	public record CheckRequest(@NotNull UUID verificationRequestId, @NotNull UUID applicationId,
			@NotNull @Min(1) @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = StrictRevision.class) Integer applicationRevision,
			@NotBlank @Pattern(regexp = "DEMO-[A-Z0-9-]{1,80}") String fixtureIdentityId) {
		@com.fasterxml.jackson.annotation.JsonAnySetter
		public void unknownField(String name, com.fasterxml.jackson.databind.JsonNode value) {
			throw new IllegalArgumentException("Unknown verification request field");
		}
	}

	public static class StrictRevision extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
		@Override
		public Integer deserialize(com.fasterxml.jackson.core.JsonParser parser,
				com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
			if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))
				throw com.fasterxml.jackson.databind.JsonMappingException.from(parser, "Revision must be an integer");
			return parser.getIntValue();
		}
	}

	@PostMapping
	@PreAuthorize("@verificationAccess.allowed(authentication)")
	public VerificationResultResponse check(@Valid @RequestBody CheckRequest request) {
		return VerificationResultResponse
				.from(simulator.verify(new VerificationSimulator.Request(request.verificationRequestId(),
						request.applicationId(), request.applicationRevision(), request.fixtureIdentityId())));
	}
}
