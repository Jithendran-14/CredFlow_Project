package com.credflow.service;

import com.credflow.platform.Platform;
import com.credflow.repository.NbfcBSimulatorRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LenderOwnershipTest {
    @Test void aPeerBankRequestNeverReachesTheDatabase() {
        var em=mock(EntityManager.class);var platform=mock(Platform.class);
        var service=new NbfcBSimulatorService(new NbfcBSimulatorRepository(em),platform,"integration-service",
            "nbfc-b","22222222-2222-4222-8222-222222222222","http://localhost:8087");
        var error=assertThrows(ResponseStatusException.class,
            ()->service.lookup("bank-a",UUID.randomUUID(),null));
        assertEquals(404,error.getStatusCode().value());
        verifyNoInteractions(em,platform);
    }

    @Test void incorrectBankIdentityFailsAtStartup() {
        assertThrows(IllegalArgumentException.class,()->new NbfcBSimulatorService(
            new NbfcBSimulatorRepository(mock(EntityManager.class)),mock(Platform.class),"integration-service",
            "bank-a","22222222-2222-4222-8222-222222222222","http://localhost:8087"));
    }

    @Test @SuppressWarnings("unchecked") void callbackWorkerOnlyPollsTheOwningBank() {
        var em=mock(EntityManager.class);var platform=mock(Platform.class);
        when(platform.tx(any())).thenAnswer(i->((Supplier<?>)i.getArgument(0)).get());
        TypedQuery<UUID> query=mock(TypedQuery.class);
        when(em.createQuery(anyString(),eq(UUID.class))).thenReturn(query);
        when(query.setParameter(anyString(),any())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        new NbfcBSimulatorService(new NbfcBSimulatorRepository(em),platform,"integration-service",
            "nbfc-b","22222222-2222-4222-8222-222222222222","http://localhost:8087").callbacks();
        verify(query).setParameter("bank","nbfc-b");
        verify(em,times(1)).createQuery(anyString(),eq(UUID.class));
    }
}
