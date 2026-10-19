package com.myxhs.demo.gateway.filter;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.security.Principal;

/**
 * L36 限流实验：{@code KeyResolver} 决定限流粒度。
 *
 * <p>为什么需要它：网关自带的默认 resolver（{@code principalNameKeyResolver}）取的是登录用户名，
 * demo 无认证永远解析出空 key → {@code denyEmptyKey} 默认 true → 直接 403，根本走不到 Lua。
 * 所以 lab 路由用这个按"接口路径"限流的 resolver：同一 path 共用一个桶，连打同一 URL 即可打出 429。
 *
 * <p>原理见 {@code docs/gateway/L36-rate-limiting.md} §36.4。
 */
@Configuration
public class RateLimitConfig {

    /**
     * lab 路由默认用的 resolver。{@code @Primary} 必不可少：SCG 的
     * {@code requestRateLimiterGatewayFilterFactory(RateLimiter, KeyResolver)} 构造器要单例注入，
     * 两个 {@code KeyResolver} bean 无 {@code @Primary} 会直接起不来（NoUniqueBeanDefinition）。
     */
    @Bean
    @Primary
    public KeyResolver pathKeyResolver() {
        return exchange -> Mono.just(exchange.getRequest().getPath().value());
    }

    /**
     * L36 §36.6 生产粒度：用户 × 接口组合（"每个用户在每个接口上各 N QPS"）。
     *
     * <p>单维度不够用：只按用户限，单个用户刷单个热点接口仍能打满它；只按接口限，一个刷子能挤占全站配额。
     * 组合 key 让"刷子"只能耗自己的桶。无认证时落到 {@code anonymous:} 前缀桶（等价于按接口限流，
     * 误伤面可控）。注意：本 bean 暂无路由引用（lab 路由用 {@code pathKeyResolver}），仅作生产写法沉淀。
     */
    @Bean
    public KeyResolver userPathKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(user -> user + ":" + exchange.getRequest().getPath().value())
                .defaultIfEmpty("anonymous:" + exchange.getRequest().getPath().value());
    }
}
