package com.myxhs.demo.gateway.filter;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;

/**
 * L37 熔断 fallback 端点：CircuitBreaker 过滤器配 {@code fallbackUri: forward:/fallback} 后，
 * 熔断 / 超时 / 命中 statusCodes 时转发到这里；把"为什么触发"的异常信息带回响应，便于实验观察。
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback")
    public Mono<Map<String, Object>> fallback(ServerWebExchange exchange) {
        Throwable t = exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fallback", true);
        body.put("path", exchange.getRequest().getPath().value());
        body.put("cause", t == null ? "none" : t.getClass().getName() + ": " + t.getMessage());
        return Mono.just(body);
    }

}
