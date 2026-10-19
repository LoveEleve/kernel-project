package com.myxhs.demo.gateway.filter;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * L38 body 改写实验路由（Java DSL）。
 *
 * <p>为什么不用 yml：{@code ModifyRequestBody} 的 inClass/outClass 是
 * {@code ParameterizedTypeReference}，yml 的字符串无法绑定（启动会报
 * "No converter found capable of converting from String to ParameterizedTypeReference"）——
 * 官方文档也明确写了 "This filter can be configured only by using the Java DSL"。
 *
 * <p>所以这条路由整体用 Java DSL 写：请求体改写（给下游加字段）+ 响应体改写（给客户端加字段）。
 */
@Configuration
public class RewriteRouteConfig {

    @Bean
    @SuppressWarnings("unchecked")
    public RouteLocator rewriteLabRoute(RouteLocatorBuilder builder) {
        return builder.routes()
            .route("rewrite-lab", r -> r.path("/rewrite-lab/**")
                .filters(f -> f.stripPrefix(1)
                    // 请求体改写：下游收到的 JSON 会被加一个 addedBy 字段
                    .modifyRequestBody(Map.class, Map.class, (exchange, body) -> {
                        Map<String, Object> out = body == null ? new LinkedHashMap<>()
                                : new LinkedHashMap<>((Map<String, Object>) body);
                        out.put("addedBy", "gateway-request-rewrite");
                        return Mono.just(out);
                    })
                    // 响应体改写：客户端收到的 JSON 会被加一个 rewrittenBy 字段
                    .modifyResponseBody(Map.class, Map.class, (exchange, body) -> {
                        Map<String, Object> out = body == null ? new LinkedHashMap<>()
                                : new LinkedHashMap<>((Map<String, Object>) body);
                        out.put("rewrittenBy", "gateway-response-rewrite");
                        return Mono.just(out);
                    }))
                .uri("http://localhost:8081"))
            .build();
    }

}
