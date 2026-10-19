package com.scalab.sentinel.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.RequestOriginParser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A-06: Sentinel origin 解析器（Spring Boot 4.x / Jakarta EE 兼容版）。
 *
 * <p>SCA 2025.1.0.0 自带 sentinel-spring-webmvc-v6x-adapter，
 * 提供了 Jakarta 兼容的 RequestOriginParser 接口。
 * 从 HTTP Header X-Caller 提取调用方身份，供 AuthorityRule 匹配。
 */
@Configuration
public class SentinelOriginConfig {

    @Bean
    public RequestOriginParser requestOriginParser() {
        return new RequestOriginParser() {
            @Override
            public String parseOrigin(HttpServletRequest request) {
                String origin = request.getHeader("X-Caller");
                return origin != null ? origin : "default";
            }
        };
    }
}
