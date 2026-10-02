package com.myxhs.demo.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;


/**
 * 自定义全局过滤器：演示 GlobalFilter 链的三件事
 * 1. 在请求进入时改请求（注入 TraceId / 标记 Header）
 * 2. 在响应返回时改响应（加响应头、记耗时）
 * 3. 用 Order 控制自己在过滤器链里的位置
 * <p>
 * 关键点：WebFlux 下没有 ThreadLocal，请求级数据放 exchange.getAttributes()。
 */
@Component
public class DemoGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DemoGlobalFilter.class);

    public static final String TRACE_ID = "X-Demo-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.nanoTime();
//        Map<String, Object> attributes = exchange.getAttributes();
        ServerHttpRequest request = exchange.getRequest();
        // 上游没带 TraceId 就生成一个（真实项目里这里对应 RequestLogFilter）
        String traceId = request.getHeaders().getFirst(TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String finalTraceId = traceId;

        // 请求级数据用 attributes 传递，不要用 ThreadLocal（EventLoop 线程会复用）
        exchange.getAttributes().put("demo.startNanos", start);

        // mutate 是不可变模式：每次都生成新的 request / exchange 对象
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r
                        .header(TRACE_ID, finalTraceId)
                        .header("X-Demo-From-Gateway", "true"))
                .build();

        log.info("[gateway] --> {} {} traceId={}", request.getMethod(), request.getURI().getPath(), finalTraceId);

        return chain.filter(mutated)
                .then(Mono.fromRunnable(() -> {
                    long costMs = (System.nanoTime() - start) / 1_000_000;
                    log.info("[gateway] <-- {} {} status={} cost={}ms traceId={}",
                            request.getMethod(), request.getURI().getPath(),
                            exchange.getResponse().getStatusCode(), costMs, finalTraceId);
                }));
    }

    @Override
    public int getOrder() {
        // 越小越先执行。日志/染色类过滤器放最前，鉴权/限流放后面
        return 100;
    }
}
