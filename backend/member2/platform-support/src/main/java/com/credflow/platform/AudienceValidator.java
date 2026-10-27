package com.credflow.platform;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.Jwt;
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {
    private final String audience;
    public AudienceValidator(String audience) { this.audience = audience; }
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        return jwt.getAudience().contains(audience) ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Required audience is missing", null));
    }
}
