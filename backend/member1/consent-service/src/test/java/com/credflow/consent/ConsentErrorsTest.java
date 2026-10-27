package com.credflow.consent;

import com.credflow.consent.controller.ConsentErrors;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ConsentErrorsTest {
    private final ConsentErrors errors = new ConsentErrors();
    @Test void domainConflictPreservesItsStatusAndCode() {
        var response=errors.controlled(new ResponseStatusException(HttpStatus.CONFLICT,"CONSENT_VERSION_MISMATCH"));
        assertEquals(409,response.getStatusCode().value());
        assertEquals("CONSENT_VERSION_MISMATCH",response.getBody().get("code"));
    }
    @Test void databaseFailureDoesNotExposeConnectionOrSqlDetails() {
        var response=errors.unavailable(new DataAccessResourceFailureException("private Oracle connection and SQL details"));
        assertEquals(503,response.getStatusCode().value());
        assertEquals("CONSENT_STORAGE_UNAVAILABLE",response.getBody().get("code"));
        assertFalse(response.getBody().toString().contains("private Oracle"));
    }
    @Test void ownershipDenialDoesNotRevealAnotherSellersIdentity() {
        var response=errors.denied(new AccessDeniedException("private seller identity"));
        assertEquals(403,response.getStatusCode().value());
        assertFalse(response.getBody().toString().contains("private seller"));
    }
}
