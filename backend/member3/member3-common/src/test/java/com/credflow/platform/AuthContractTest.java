package com.credflow.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Member 1 custom Auth identity rules; no live tokens or database required.
 */
class AuthContractTest {

    private Platform platform(MockEnvironment env) {
        return new Platform(env, new ObjectMapper(), mock(PlatformTransactionManager.class));
    }

    private final Platform p = platform(new MockEnvironment());

    private Jwt jwt(String subject, Map<String, Object> claims) {
        return Jwt.withTokenValue("test").header("alg", "RS256").subject(subject).claims(values -> values.putAll(claims)).build();
    }

    @Test
    void trustedStringSellerIdentityOwnsResourceRegardlessOfSubjectFormat() {
        Jwt seller = jwt("user-record-13", Map.of("roles", List.of("SELLER"), "sellerId", "SELLER-1001"));
        assertDoesNotThrow(() -> p.owner(seller, null, "SELLER-1001", null, null, false));
        assertThrows(ResponseStatusException.class, () -> p.owner(seller, "user-record-13", "SELLER-2002", null, null, true));
        assertThrows(ResponseStatusException.class, () -> p.owner(jwt("user-record-13", Map.of("roles", List.of("SELLER"))), "user-record-13", "SELLER-1001", null, null, true));
    }

    @Test
    void sellerSubjectFallbackRequiresStoredTrustedSubjectAndNoStoredSellerId() {
        Jwt seller = jwt("subject-1", Map.of("role", "SELLER"));
        assertDoesNotThrow(() -> p.owner(seller, "subject-1", null, null, null, false));
        assertThrows(ResponseStatusException.class, () -> p.owner(seller, null, null, null, null, true));
        assertThrows(ResponseStatusException.class, () -> p.owner(seller, "subject-2", null, null, null, true));
    }

    @Test
    void marketplaceRoleCanReadOnlyItsOwnStatusResources() {
        UUID marketplaceId = UUID.randomUUID();
        Jwt marketplace = jwt("operator", Map.of("roles", "MARKETPLACE", "marketplaceId", marketplaceId.toString()));
        assertTrue(p.hasRole(marketplace, "MARKETPLACE_OPERATIONS"));
        assertDoesNotThrow(() -> p.owner(marketplace, null, "SELLER-1001", null, marketplaceId, true));
        assertThrows(ResponseStatusException.class, () -> p.owner(marketplace, null, "SELLER-1001", null, UUID.randomUUID(), true));
        assertThrows(ResponseStatusException.class, () -> p.owner(marketplace, null, "SELLER-1001", null, marketplaceId, false));
    }

    @Test
    void camelIdentityTakesPrecedenceOverLegacyClaimsWithoutAmbiguousFallback() {
        Jwt identity = jwt("user", Map.of("sellerId", "SELLER-1001", "seller_id", "SELLER-2002", "marketplaceId", "new-market", "marketplace_id", "old-market", "lenderId", "new-lender", "lender_id", "old-lender"));
        assertEquals("SELLER-1001", p.sellerId(identity));
        assertEquals("new-market", p.marketplaceId(identity));
        assertEquals("new-lender", p.lenderId(identity));
        assertNull(p.sellerId(jwt("user", Map.of("sellerId", "", "seller_id", "SELLER-2002"))));
        assertEquals("SELLER-1001", p.sellerId(jwt("user", Map.of("seller_id", "SELLER-1001"))));
    }

    @Test
    void keycloakRoleClaimsRequireExplicitMode() {
        Jwt legacy = jwt("admin", Map.of("resource_access", Map.of("credflow-ui", Map.of("roles", List.of("ADMIN")))));
        assertFalse(p.hasRole(legacy, "ADMIN"));
        var keycloak = platform(new MockEnvironment().withProperty("credflow.security.role-mode", "KEYCLOAK"));
        assertTrue(keycloak.hasRole(legacy, "ADMIN"));
        assertFalse(keycloak.hasRole(jwt("admin", Map.of("roles", List.of("ADMIN"))), "ADMIN"));
    }

    @Test
    void roleClaimIsConfigurableAndDoesNotGrantUnlistedLenderRoles() {
        var custom = platform(new MockEnvironment().withProperty("credflow.security.role-claim", "authorities"));
        Jwt admin = jwt("admin", Map.of("authorities", "ADMIN,SELLER"));
        assertTrue(custom.hasRole(admin, "ADMIN"));
        assertTrue(custom.hasRole(admin, "SELLER"));
        assertFalse(custom.hasRole(admin, "LENDER_OFFICER"));
        assertFalse(custom.hasRole(jwt("admin", Map.of("roles", List.of("ADMIN"))), "ADMIN"));
    }

    @Test
    void adminDoesNotImpersonateDedicatedServiceClient() {
        Jwt admin = jwt("admin", Map.of("roles", List.of("ADMIN"), "scope", "integration.write"));
        assertThrows(ResponseStatusException.class, () -> p.machine(admin, "integration.write", "orchestration-service"));
        Jwt wrongClient = jwt("service", Map.of("client_id", "eligibility-service", "scp", List.of("integration.write")));
        assertThrows(ResponseStatusException.class, () -> p.machine(wrongClient, "integration.write", "orchestration-service"));
        Jwt rightClient = jwt("service", Map.of("client_id", "orchestration-service", "scope", "integration.write"));
        assertDoesNotThrow(() -> p.machine(rightClient, "integration.write", "orchestration-service"));
    }

    @Test
    void missingMachineGrantConfigurationStopsBeforeAnyOutboundCall() {
        var error = assertThrows(ResponseStatusException.class, () -> p.call("system", HttpMethod.POST, "http://127.0.0.1:9/internal/v1/test", Map.of(), "id", "trace"));
        assertEquals(503, error.getStatusCode().value());
        assertEquals("SERVICE_AUTHENTICATION_NOT_CONFIGURED", error.getReason());
    }
}
