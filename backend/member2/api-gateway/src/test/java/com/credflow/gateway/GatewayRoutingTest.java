package com.credflow.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayRoutingTest {
    static final Map<String,HttpServer> servers=new LinkedHashMap<>();
    static {
        try {
            for(String owner:List.of("APPLICATION","CONSENT","ELIGIBILITY","ORCHESTRATION","INTEGRATION","AUDIT")) {
                var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/",exchange -> {
                    String correlation=exchange.getRequestHeaders().getFirst("X-Correlation-ID");
                    boolean bearer="Bearer forwarded-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] body=("{\"owner\":\""+owner+"\",\"correlationId\":\""+correlation+"\",\"bearerForwarded\":"+bearer+"}").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(200,body.length);
                    try(var out=exchange.getResponseBody()) { out.write(body); }
                });
                server.start(); servers.put(owner,server);
            }
        } catch(Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void urls(DynamicPropertyRegistry registry) {
        servers.forEach((owner,server)->registry.add(owner+"_URL",()->"http://127.0.0.1:"+server.getAddress().getPort()));
    }
    @Autowired WebTestClient client;
    @MockitoBean ReactiveJwtDecoder decoder;
    @BeforeEach void decodedTestToken() {
        var token=org.springframework.security.oauth2.jwt.Jwt.withTokenValue("forwarded-test-token")
            .header("alg","RS256").subject("test-user").audience(List.of("credflow-api")).build();
        org.mockito.Mockito.when(decoder.decode("forwarded-test-token")).thenReturn(reactor.core.publisher.Mono.just(token));
    }
    @ParameterizedTest @CsvSource({
        "/api/v1/applications,APPLICATION",
        "/api/v1/applications/demo/consents,CONSENT",
        "/api/v1/applications/demo/verification,CONSENT",
        "/api/v1/applications/demo/eligibility,ELIGIBILITY",
        "/api/v1/applications/demo/offers,ORCHESTRATION",
        "/api/v1/submissions/demo/decisions,INTEGRATION",
        "/api/v1/applications/demo/audit,AUDIT",
        "/api/v1/system/application-service/info,APPLICATION",
        "/api/v1/system/consent-verification-service/info,CONSENT"
    })
    void routesSpecificPathsBeforeApplicationFallbackAndForwardsHeaders(String path,String owner) {
        client.get().uri(path)
            .header("Authorization","Bearer forwarded-test-token").header("X-Correlation-ID","route-test")
            .exchange().expectStatus().isOk().expectBody()
            .jsonPath("$.owner").isEqualTo(owner)
            .jsonPath("$.correlationId").isEqualTo("route-test")
            .jsonPath("$.bearerForwarded").isEqualTo(true);
    }
    @AfterAll static void stopServers() { servers.values().forEach(s->s.stop(0)); }
}
