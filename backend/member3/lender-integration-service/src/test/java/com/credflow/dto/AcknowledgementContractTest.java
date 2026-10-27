package com.credflow.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AcknowledgementContractTest {
    @Test void typedAcknowledgementsPreserveTheExistingWireFields() throws Exception {
        var mapper=new ObjectMapper();
        UUID id=UUID.randomUUID(),operation=UUID.randomUUID();
        assertEquals(mapper.readTree("{\"submissionId\":\""+id+"\",\"operationId\":\""+operation+"\",\"status\":\"QUEUED\"}"),
            mapper.valueToTree(new SubmissionQueuedResponse(id,operation,"QUEUED")));
        assertEquals(mapper.readTree("{\"eventId\":\""+id+"\",\"status\":\"PENDING\"}"),
            mapper.valueToTree(new CallbackAcceptedResponse(id,"PENDING")));
        assertEquals(mapper.readTree("{\"operationId\":\""+id+"\",\"status\":\"PENDING\"}"),
            mapper.valueToTree(new OperationReplayResponse(id,"PENDING")));
    }
}
