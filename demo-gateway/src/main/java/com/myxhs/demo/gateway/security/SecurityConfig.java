package com.myxhs.demo.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * L39 认证实验：网关做认证的最小形态（HTTP Basic —— 仅 Demo 用）。
 *
 * <p>规则：只对 {@code /secure-lab/**} 要求认证；其余路径全部放行（不影响其他 lab 路由）。
 * <p>两个 Demo 简化：① 认证方式用 Basic，生产换成 JWT 资源服务器（
 * {@code oauth2ResourceServer(jwt)}）或 OAuth2 客户端（登录换取 token）；② 关掉 CSRF，
 * 否则带 body 的 POST 路由会被拦——生产网关要单独评估 CSRF 策略。
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(ex -> ex
                .pathMatchers("/secure-lab/**").authenticated()
                .anyExchange().permitAll())
            .httpBasic(Customizer.withDefaults())
            .build();
    }

}
