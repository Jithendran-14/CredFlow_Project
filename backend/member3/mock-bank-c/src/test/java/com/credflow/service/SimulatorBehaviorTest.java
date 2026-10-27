package com.credflow.service;

import com.credflow.entity.SimCallback;
import com.credflow.entity.SimCase;
import com.credflow.platform.Platform;
import com.credflow.repository.BankCSimulatorRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SimulatorBehaviorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final Platform platform = mock(Platform.class);

    private final EntityManager em = mock(EntityManager.class);

    @Test
    void lateAnswersAreAcknowledgedWithoutReplacingAnAvailableOffer() throws Exception {
        assertAnswerBehavior("APPROVED", "APPROVED");
    }

    @Test
    void completedAnswersResumeInformationRequiredApplication() throws Exception {
        assertAnswerBehavior("ACTION_REQUIRED", "UNDER_REVIEW");
    }

    private void assertAnswerBehavior(String before, String after) throws Exception {
        when(platform.write(any())).thenAnswer(invocation -> invocation.getArgument(0).toString());
        var simulator = new BankCSimulatorService(new BankCSimulatorRepository(em), platform, "integration-service", "bank-c", "11111111-1111-4111-8111-111111111111", "http://localhost:8085");
        SimCase application = new SimCase();
        application.setId("bank-c:" + UUID.randomUUID());
        application.setLenderCode("bank-c");
        application.setLenderId("11111111-1111-4111-8111-111111111111");
        application.setApplicationId(UUID.randomUUID());
        application.setSubmissionId(UUID.randomUUID());
        application.setExternalId("BA-" + application.getSubmissionId());
        application.setRevision(1);
        application.setStatus(before);
        UUID informationId = UUID.randomUUID();
        ObjectNode state = mapper.createObjectNode();
        ObjectNode storedQuestion = mapper.createObjectNode();
        storedQuestion.put("informationRequestId", informationId.toString());
        storedQuestion.putArray("questions").addObject().put("questionId", "Q1").put("prompt", "Explain the synthetic sales variation");
        state.withObject("/informationRequests").set(informationId.toString(), storedQuestion);
        ObjectNode answer = mapper.createObjectNode();
        answer.put("informationRequestId", informationId.toString());
        answer.putArray("answers").addObject().put("questionId", "Q1").put("answerText", "Synthetic seasonal variation");
        answer.putArray("syntheticDocumentReferences");
        ObjectNode actor = mapper.createObjectNode().put("type", "HUMAN").put("subject", "seller-1");
        ReflectionTestUtils.invokeMethod(simulator, "answers", application, state, answer, actor);
        assertEquals(after, application.getStatus());
        assertEquals(answer, state.path("answers").path(informationId.toString()));
        assertEquals(1, application.getSequence());
        var saved = ArgumentCaptor.forClass(SimCallback.class);
        verify(em).persist(saved.capture());
        SimCallback callback = saved.getValue();
        assertEquals("PENDING", callback.getStatus());
        assertEquals(1, callback.getSequence());
        JsonNode event = mapper.readTree(callback.getPayload());
        assertEquals("lender.information.answered", event.path("eventType").asText());
        assertEquals(answer, event.path("data"));
    }
}
