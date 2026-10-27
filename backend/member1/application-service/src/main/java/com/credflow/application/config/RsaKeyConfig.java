
package com.credflow.application.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class RsaKeyConfig {

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${credflow.jwt.public-key-path}") String publicKeyPath,
            @Value("${credflow.jwt.issuer}") String issuer,
            @Value("${credflow.jwt.audience}") String audience) {

        try {
            String pem = Files.readString(Path.of(publicKeyPath));

            String encodedKey = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");

            byte[] keyBytes = Base64.getDecoder().decode(encodedKey);

            X509EncodedKeySpec keySpec =
                    new X509EncodedKeySpec(keyBytes);

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            RSAPublicKey publicKey = (RSAPublicKey)
                    keyFactory.generatePublic(keySpec);

            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withPublicKey(publicKey)
                    .build();

            OAuth2TokenValidator<Jwt> issuerValidator =
                    JwtValidators.createDefaultWithIssuer(issuer);

            OAuth2TokenValidator<Jwt> audienceValidator =
                    new JwtClaimValidator<List<String>>(
                            "aud",
                            audiences -> audiences != null
                                    && audiences.contains(audience));

            decoder.setJwtValidator(
                    new DelegatingOAuth2TokenValidator<>(
                            issuerValidator,
                            audienceValidator));

            return decoder;

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to configure JWT decoder. Check the public key path and PEM file.",
                    exception);
        }
    }
}
