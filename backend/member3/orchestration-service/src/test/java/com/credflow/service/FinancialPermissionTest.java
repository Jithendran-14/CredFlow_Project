package com.credflow.service;

import com.credflow.entity.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FinancialPermissionTest {
    final DocumentAlignmentTest f=new DocumentAlignmentTest();
    JsonNode permission(WorkflowRow w,String status,Instant expiry) {
        return f.mapper.valueToTree(Map.of("applicationId",w.getId().toString(),"applicationRevision",w.getRevision(),
            "status",status,"operationalConsentVersion",w.getConsentVersion(),"expiresAt",expiry.toString(),"financialConsentVersion",1));
    }
    void lender(WorkflowRow w) throws Exception {
        w.setLenderId("bank-a");w.setSubmissionId(UUID.randomUUID());w.setDispatchAuthorized(true);
        w.setConsentJson("{\"recipientLenderIds\":[\"bank-a\"]}");
    }
    @Test void expiryPausesApprovedOfferAndRenewalRestoresSameSubmission() throws Exception {
        var w=f.workflow("APPROVED");lender(w);UUID submission=w.getSubmissionId();w.setDetail("APPROVAL_RECEIVED_AWAITING_OFFER");
        f.service.applyFinancialPermission(w,permission(w,"EXPIRED",Instant.now().minusSeconds(1)),null);
        assertEquals("ACTION_REQUIRED",w.getStatus());assertEquals("FINANCIAL_CONSENT_EXPIRED",w.getDetail());assertFalse(w.isDispatchAuthorized());
        long version=w.getWorkflowVersion();f.service.applyFinancialPermission(w,permission(w,"EXPIRED",Instant.now().minusSeconds(1)),null);
        assertEquals(version,w.getWorkflowVersion());
        f.service.applyFinancialPermission(w,permission(w,"ACTIVE",Instant.now().plusSeconds(1000)),null);
        assertEquals("APPROVED",w.getStatus());assertEquals("APPROVAL_RECEIVED_AWAITING_OFFER",w.getDetail());
        assertEquals(submission,w.getSubmissionId());assertTrue(w.isDispatchAuthorized());
    }
    @Test void withdrawnPermissionResumesExistingQuestionRatherThanCreatingAnotherApplication() throws Exception {
        var w=f.workflow("ACTION_REQUIRED");lender(w);w.setDetail("LENDER_INFORMATION_REQUIRED");
        f.service.applyFinancialPermission(w,permission(w,"REVOKED",Instant.now().plusSeconds(1000)),null);
        assertEquals("FINANCIAL_CONSENT_WITHDRAWN",w.getDetail());
        f.service.applyFinancialPermission(w,permission(w,"ACTIVE",Instant.now().plusSeconds(1000)),null);
        assertEquals("ACTION_REQUIRED",w.getStatus());assertEquals("LENDER_INFORMATION_REQUIRED",w.getDetail());
        verify(f.p,never()).job(any(),anyString(),anyString(),any(),anyString(),any(),any());
    }
    @Test void timestampsBlockSharingEvenBeforeExpiryEventArrives() throws Exception {
        var w=f.workflow("UNDER_REVIEW");lender(w);
        var submission=new SubmissionRow();submission.setId(w.getSubmissionId());submission.setApplicationId(w.getId());submission.setRevision(1);
        when(f.em.find(SubmissionRow.class,submission.getId())).thenReturn(submission);
        when(f.p.call(eq("consent"),any(),anyString(),isNull(),isNull(),anyString())).thenReturn(permission(w,"ACTIVE",Instant.now().minusSeconds(1)));
        assertFalse(f.service.authorization(submission.getId(),null).path("authorized").asBoolean());
    }
    @Test void terminalApplicationIsNeverReopenedByRenewalOrExpiry() throws Exception {
        var w=f.workflow("REJECTED");
        f.service.applyFinancialPermission(w,permission(w,"EXPIRED",Instant.now().minusSeconds(1)),null);
        assertEquals("REJECTED",w.getStatus());verify(f.p,never()).emit(anyString(),any(),anyLong(),any(),any(),any(),any());
    }
    @Test void manualReviewCreatesTheExactLenderTaskAndDuplicateQuestionCallbackIsHarmless() throws Exception {
        var w=f.workflow("UNDER_REVIEW");var submission=f.submission(w);
        var d=f.bound(w,submission);UUID question=UUID.randomUUID();
        d.put("decisionId",UUID.randomUUID());d.put("decision","MANUAL_REVIEW");d.put("decidedAt",Instant.now().toString());
        d.put("informationRequestId",question);d.put("questions",List.of(Map.of("questionId","sales","prompt","Explain the sales decline in June.")));
        f.service.onEvent(f.event(w,"lender.decision.received",d,1));
        assertEquals("ACTION_REQUIRED",w.getStatus());
        var capture=org.mockito.ArgumentCaptor.forClass(InformationRow.class);verify(f.em).persist(capture.capture());
        var row=capture.getValue();assertEquals(question,row.getId());assertEquals(submission.getId(),row.getSubmissionId());
        assertEquals("Explain the sales decline in June.",f.mapper.readTree(row.getQuestionsJson()).get(0).path("prompt").asText());
        when(f.em.find(InformationRow.class,question)).thenReturn(row);
        f.service.onEvent(f.event(w,"lender.information.requested",d,2));
        verify(f.em,times(1)).persist(any());
    }
    @Test void rejectionExposesPermittedCodeAndKeepsPrivateReviewerNoteOutOfWorkflowView() throws Exception {
        var w=f.workflow("UNDER_REVIEW");var submission=f.submission(w);var d=f.bound(w,submission);
        d.put("decisionId",UUID.randomUUID());d.put("decision","REJECTED");d.put("decidedAt",Instant.now().toString());
        d.put("reasonCode","AFFORDABILITY_REQUIREMENT_NOT_MET");d.put("reasonText","Private underwriting discussion");
        f.service.onEvent(f.event(w,"lender.decision.received",d,1));
        assertEquals("REJECTED",w.getStatus());assertEquals("AFFORDABILITY_REQUIREMENT_NOT_MET",w.getDetail());
        var method=WorkflowService.class.getDeclaredMethod("view",WorkflowRow.class);method.setAccessible(true);
        JsonNode view=(JsonNode)method.invoke(f.service,w);
        assertEquals(w.getDetail(),view.path("reasonCode").asText());assertFalse(view.toString().contains("Private underwriting"));
        w.setDetail(null);view=(JsonNode)method.invoke(f.service,w);
        assertEquals("AFFORDABILITY_REQUIREMENT_NOT_MET",view.path("reasonCode").asText());
    }
    @Test void operationalWithdrawalCannotBeOverriddenByFinancialRenewal() throws Exception {
        var w=f.workflow("UNDER_REVIEW");lender(w);
        f.service.applyFinancialPermission(w,permission(w,"EXPIRED",Instant.now().minusSeconds(1)),null);
        w.setConsentGranted(false);w.setDetail("CONSENT_REVOKED");
        f.service.applyFinancialPermission(w,permission(w,"ACTIVE",Instant.now().plusSeconds(1000)),null);
        assertEquals("ACTION_REQUIRED",w.getStatus());assertFalse(w.isDispatchAuthorized());
        assertTrue(f.mapper.readTree(w.getFinancialPermissionJson()).has("resumeStatus"));
    }
}
