package com.credflow.gateway;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationWebFilter implements WebFilter {
    public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        String supplied=exchange.getRequest().getHeaders().getFirst("X-Correlation-ID");
        String id=supplied!=null && supplied.matches("[A-Za-z0-9._-]{1,100}") ? supplied : UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("X-Correlation-ID",id);
        var request=exchange.getRequest().mutate().headers(h -> h.set("X-Correlation-ID",id)).build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}
