package com.credflow.application.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.credflow.application.dto.ApplicationRequest;

@Component
public class RequestFingerprint {

    private final ObjectMapper objectMapper;

    public RequestFingerprint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String calculate(ApplicationRequest request) {
        try {
            byte[] requestJson = objectMapper.writeValueAsBytes(request);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(requestJson);

            return HexFormat.of().formatHex(hash);

        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Unable to serialize application request",
                    exception
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is unavailable",
                    exception
            );
        }
    }
}
