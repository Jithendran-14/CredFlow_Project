package com.credflow.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical lender wire data. Unknown fields never become business events.
 */
public final class LenderCommandPayloads {

    private LenderCommandPayloads() {
    }

    private static final List<String> CONTEXT = List.of(
        "applicationId",
        "submissionId",
        "applicationRevision",
        "lenderId",
        "correlationId",
        "causationId"
    );

    private static final List<String> OFFER = List.of(
        "offerId",
        "offeredAmount",
        "currency",
        "annualInterestRatePercent",
        "interestRateType",
        "tenureMonths",
        "repaymentFrequency",
        "feesAmount",
        "termsReference",
        "expiresAt",
        "version"
    );

    public static ObjectNode command(String operation, JsonNode source) {
        List<String> fields = new ArrayList<>(CONTEXT);
        fields.addAll(switch(operation) {
            case "DECISION" ->
                List.of("decisionId", "decision", "reasonCode", "reasonText", "decidedAt", "offer", "informationRequestId", "questions");
            case "REQUEST_INFORMATION" ->
                List.of("informationRequestId", "questions");
            case "ANSWER_INFORMATION" ->
                List.of("informationRequestId", "answers", "syntheticDocumentReferences", "answeredAt");
            case "CREATE_OFFER" ->
                OFFER;
            case "WITHDRAW_OFFER" ->
                List.of("offerId", "reasonCode", "reason");
            case "ACCEPT_OFFER" ->
                List.of("offerId", "acceptanceId", "acceptedAt", "expectedOfferVersion", "operationAt");
            case "DECLINE_OFFER" ->
                List.of("offerId", "declineId", "expectedOfferVersion", "operationAt");
            case "CANCEL" ->
                List.of("cancellationId", "reason");
            default ->
                throw new IllegalArgumentException("INVALID_COMMAND");
        });
        ObjectNode canonical = select(source, fields);
        if (canonical.has("offer")) {
            ObjectNode offer = select(canonical.path("offer"), OFFER);
            if (!offer.has("version"))
                offer.put("version", 0);
            canonical.set("offer", offer);
        }
        structuredArrays(canonical);
        return canonical;
    }

    public static ObjectNode event(String topic, JsonNode source) {
        // Trace belongs to the trusted event envelope, never lender-supplied
        // business data. Command transport context remains intact above.
        List<String> fields = new ArrayList<>();
        fields.addAll(switch(topic) {
            case "lender.decision.received" ->
                List.of("decisionId", "decision", "reasonCode", "reasonText", "decidedAt", "offer", "informationRequestId", "questions");
            case "lender.information.requested" ->
                List.of("informationRequestId", "questions", "decisionId", "decision", "decidedAt", "createdAt");
            case "lender.information.answered" ->
                List.of("informationRequestId", "answers", "syntheticDocumentReferences", "answeredAt", "lenderAcknowledgementStatus");
            case "loan.offer.created" ->
                OFFER;
            case "loan.offer.acknowledged" ->
                List.of("offerId", "acceptanceId", "acceptedAt", "operationAt", "expectedOfferVersion", "lenderAcknowledgementStatus", "failureCode", "outcomeUnknown", "taskId", "operation");
            case "loan.offer.withdrawn" ->
                List.of("offerId", "reasonCode", "reason");
            case "lender.cancellation.acknowledged" ->
                List.of("cancellationId", "cancellationByReference", "cancellationAcknowledgementStatus");
            default ->
                throw new IllegalArgumentException("INVALID_CALLBACK_TYPE");
        });
        ObjectNode canonical = select(source, fields);
        if (canonical.has("offer")) {
            ObjectNode offer = select(canonical.path("offer"), OFFER);
            if (!offer.has("version"))
                offer.put("version", 0);
            canonical.set("offer", offer);
        }
        structuredArrays(canonical);
        if ("loan.offer.created".equals(topic)) {
            canonical.put("version", 0);
        }
        return canonical;
    }

    private static ObjectNode select(JsonNode source, List<String> fields) {
        if (!source.isObject())
            throw new IllegalArgumentException("OBJECT_REQUIRED");
        ObjectNode selected = JsonNodeFactory.instance.objectNode();
        for (String field : fields) {
            if (source.hasNonNull(field))
                selected.set(field, source.get(field).deepCopy());
        }
        return selected;
    }

    private static void structuredArrays(ObjectNode data) {
        if (data.has("questions")) {
            if (!data.path("questions").isArray())
                throw new IllegalArgumentException("INVALID_QUESTIONS");
            ArrayNode questions = JsonNodeFactory.instance.arrayNode();
            for (JsonNode question : data.path("questions")) {
                questions.add(select(question, List.of("questionId", "prompt")));
            }
            data.set("questions", questions);
        }
        if (data.has("answers")) {
            if (!data.path("answers").isArray())
                throw new IllegalArgumentException("INVALID_ANSWERS");
            ArrayNode answers = JsonNodeFactory.instance.arrayNode();
            for (JsonNode answer : data.path("answers")) {
                if (answer.has("answer") || answer.has("documentReferences")) {
                    throw new IllegalArgumentException("USE_ANSWER_TEXT_AND_SYNTHETIC_REFERENCES");
                }
                answers.add(select(answer, List.of("questionId", "answerText")));
            }
            data.set("answers", answers);
        }
    }
}
