package com.credflow.platform;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.assertThat;
class AudienceValidatorTest {
    @Test void rejectsTokensIssuedForAnotherApi() {
        Jwt jwt=Jwt.withTokenValue("test").header("alg","RS256").subject("user").audience(java.util.List.of("other-api")).issuedAt(Instant.now()).build();
        assertThat(new AudienceValidator("credflow-api").validate(jwt).hasErrors()).isTrue();
    }
    @Test void acceptsExpectedAudience() {
        Jwt jwt=Jwt.withTokenValue("test").header("alg","RS256").subject("user").audience(java.util.List.of("credflow-api")).build();
        assertThat(new AudienceValidator("credflow-api").validate(jwt).hasErrors()).isFalse();
    }
}
