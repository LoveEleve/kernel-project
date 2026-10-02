package com.myxhs.demo.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicLong;

/**
 * L18 实验：自定义 WebFilter —— 验证三件事
 * 1. 【路由匹配之前】生效（GlobalFilter 只在"匹配到路由"之后跑）
 * 2. 【所有 HTTP 请求】都过它（连 /actuator/** 也看得见）
 * 3. 可以直接【短路】（不调 chain.filter 就到此为止，后面的 DispatcherHandler / 路由匹配全不执行）
 * <p>
 * 接入方式：注册成 WebFilter bean 即可 —— WebHttpHandlerBuilder 按类型收集 + 按 order 排序。
 */
@Component
public class DemoWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DemoWebFilter.class);

    private static final AtomicLong COUNTER = new AtomicLong();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        long seq = COUNTER.incrementAndGet();

        // ① 证明"匹配前生效 + 所有请求可见"：每个请求都打一行（含 /actuator/**）
        log.info("[DemoWebFilter] 匹配前生效 #{} {}", seq, path);

        // ② 短路实验：不调 chain.filter → 后面的（DispatcherHandler / 路由匹配）完全不执行
        if (path.contains("/web-filter-block")) {
            log.info("[DemoWebFilter] 短路拦截: {}（没走路由匹配，也没进过滤器链）", path);
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        // ③ 正常放行：往属性袋放数据 + 加响应头（curl 可见，证明确实跑过）
        exchange.getAttributes().put("demoWebFilter.seq", seq);
        exchange.getResponse().getHeaders().add("X-Demo-WebFilter", "hit-" + seq);
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;   // 控制它在 WebFilter 链里的位置
    }
}
