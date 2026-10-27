package com.credflow.eligibility;

import com.credflow.eligibility.api.EligibilityMachineAccess;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

class EligibilityMachineAccessTest {
    JwtAuthenticationToken token(Map<String,Object> claims){var b=Jwt.withTokenValue("test-only").header("alg","RS256").subject("machine-test");claims.forEach(b::claim);return new JwtAuthenticationToken(b.build(),List.of());}
    final EligibilityMachineAccess access=new EligibilityMachineAccess("orchestration-service","test.evaluate");
    @Test void exactClientAndScopeAccepted(){assertThat(access.allowed(token(Map.of("azp","orchestration-service","scope","read test.evaluate")))).isTrue();}
    @Test void scpCollectionAccepted(){assertThat(access.allowed(token(Map.of("client_id","orchestration-service","scp",List.of("test.evaluate"))))).isTrue();}
    @Test void blankConfigurationDeniesAccess(){assertThat(new EligibilityMachineAccess("orchestration-service","").allowed(token(Map.of("azp","orchestration-service","scope","test.evaluate")))).isFalse();}
    @Test void roleDoesNotReplaceClientIdentity(){assertThat(access.allowed(token(Map.of("roles",List.of("ADMIN"),"scope","test.evaluate")))).isFalse();}
    @Test void wrongClientDenied(){assertThat(access.allowed(token(Map.of("azp","seller-client","scope","test.evaluate")))).isFalse();}
    @Test void conflictingClientClaimsDenied(){assertThat(access.allowed(token(Map.of("azp","orchestration-service","client_id","other-service","scope","test.evaluate")))).isFalse();}
    @Test void partialScopeNameDoesNotGrantAccess(){assertThat(access.allowed(token(Map.of("azp","orchestration-service","scope","test.evaluate.extra")))).isFalse();}
}
