package com.credflow.platform;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiErrorContractTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CommandApi()).setControllerAdvice(new PlatformErrors()).build();

    @RestController
    static class CommandApi {

        @PostMapping(value = "/command", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> command(@RequestHeader("Idempotency-Key") String key, @RequestBody Map<String, Object> body) {
            return Map.of("operationId", key);
        }
    }

    @Test
    void missingIdempotencyHeaderIsAClientErrorWithTheAgreedShape() throws Exception {
        mvc.perform(post("/command").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(jsonPath("$.correlationId").exists()).andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void unsupportedContentTypeKeepsItsHttpMeaning() throws Exception {
        mvc.perform(post("/command").header("Idempotency-Key", "test").contentType(MediaType.TEXT_PLAIN).content("text")).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unsupportedResponseFormatReturns406() throws Exception {
        mvc.perform(post("/command").header("Idempotency-Key", "test").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML).content("{}")).andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }
}
