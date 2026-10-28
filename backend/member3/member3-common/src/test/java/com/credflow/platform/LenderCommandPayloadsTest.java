package com.credflow.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LenderCommandPayloadsTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void commandAndNestedOfferDiscardUnknownFieldsAndInitializeOnlyMissingVersion() {
		ObjectNode command = mapper.createObjectNode().put("decision", "APPROVED").put("decisionId", "test-id")
				.put("untrustedExtension", "discard");
		ObjectNode terms = mapper.createObjectNode().put("offerId", "offer-id").put("offeredAmount", "100.00")
				.put("secret", "discard");
		command.set("offer", terms);
		ObjectNode canonical = LenderCommandPayloads.command("DECISION", command);
		assertFalse(canonical.has("untrustedExtension"));
		assertFalse(canonical.path("offer").has("secret"));
		assertEquals(0, canonical.path("offer").path("version").asInt());
		terms.put("version", 5);
		assertEquals(5, LenderCommandPayloads.command("DECISION", command).path("offer").path("version").asInt(),
				"Validation must see an invalid supplied version");
	}

	@Test
	void structuredAnswersCannotUseLegacyTextOrRealDocumentReferences() {
		ObjectNode command = mapper.createObjectNode().put("informationRequestId", "request");
		ObjectNode answer = command.putArray("answers").addObject().put("questionId", "Q1").put("answer",
				"Legacy value");
		assertThrows(IllegalArgumentException.class,
				() -> LenderCommandPayloads.command("ANSWER_INFORMATION", command));
		answer.remove("answer");
		answer.put("answerText", "Canonical synthetic answer");
		answer.putArray("documentReferences").add("https://real-doc.invalid/private");
		assertThrows(IllegalArgumentException.class,
				() -> LenderCommandPayloads.command("ANSWER_INFORMATION", command));
	}

	@Test
	void callbackNormalizationKeepsCanonicalAnswersAndDiscardsUnknownNestedProperties() {
		ObjectNode callback = mapper.createObjectNode().put("informationRequestId", "request").put("unknown", "discard")
				.put("correlationId", "body-trace").put("causationId", "body-cause");
		callback.putArray("answers").addObject().put("questionId", "Q1").put("answerText", "Synthetic answer")
				.put("unknown", "discard");
		callback.putArray("syntheticDocumentReferences").add("synthetic://demo/inventory");
		ObjectNode canonical = LenderCommandPayloads.event("lender.information.answered", callback);
		assertFalse(canonical.has("unknown"));
		assertFalse(canonical.has("correlationId"));
		assertFalse(canonical.has("causationId"));
		assertFalse(canonical.path("answers").get(0).has("unknown"));
		assertEquals("Synthetic answer", canonical.path("answers").get(0).path("answerText").asText());
		assertEquals(callback.path("syntheticDocumentReferences"), canonical.path("syntheticDocumentReferences"));
	}
}
