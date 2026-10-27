package com.credflow.consent;

import com.credflow.consent.service.FinancialConsentEvents;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FinancialConsentEventsTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ObjectMapper mapper=new ObjectMapper();
    final FinancialConsentEvents events=new FinancialConsentEvents(jdbc,mapper);
    @Test void lifecycleEnvelopeContainsOnlyPermissionReferencesAndOriginalActorTime() {
        UUID grant=UUID.randomUUID(),application=UUID.randomUUID();
        when(jdbc.queryForMap(anyString(),eq(grant.toString()))).thenReturn(Map.of("APPLICATION_ID",application.toString(),"APPLICATION_REVISION",2,"CONSENT_VERSION",3,"OPERATIONAL_CONSENT_VERSION",4));
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq(application.toString()))).thenReturn(9L);
        OffsetDateTime at=OffsetDateTime.parse("2026-10-20T08:00:00Z");
        events.append(grant,"granted",at,"seller-original","original-correlation");
        var value=org.mockito.ArgumentCaptor.forClass(SqlParameterValue.class);
        verify(jdbc).update(contains("INSERT INTO CF_OUTBOX_EVENTS"),anyString(),eq(application.toString()),eq(10L),eq("financial.consent.granted"),eq("1.0"),eq("original-correlation"),eq(grant.toString()),value.capture());
        var event=mapper.readTree((String)value.getValue().getValue());
        assertEquals("seller-original",event.path("actor").path("subject").asText());
        assertEquals(at.toString(),event.path("occurredAt").asText());assertEquals("ACTIVE",event.path("data").path("status").asText());
        assertEquals(5,event.path("data").size());assertFalse(event.toString().contains("password"));
    }
    @Test void unknownActionIsRejectedBeforeAnyDatabaseWrite(){
        assertThrows(IllegalArgumentException.class,()->events.append(UUID.randomUUID(),"unknown",OffsetDateTime.now(),"seller","trace"));
        verifyNoInteractions(jdbc);
    }
}
