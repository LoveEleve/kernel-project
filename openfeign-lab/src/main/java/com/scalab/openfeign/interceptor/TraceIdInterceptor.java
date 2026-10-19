package com.scalab.openfeign.interceptor;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * A-02: 自定义 RequestInterceptor。
 * 每次 Feign 请求自动注入 X-Trace-Id Header,用于全链路追踪。
 */
@Component
public class TraceIdInterceptor implements RequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TraceIdInterceptor.class);

    /** Header 被透传到 nacos-lab,调用方可以收到并做追踪 */
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get(TRACE_ID_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        template.header(TRACE_ID_HEADER, traceId);

        // 同时注入调用方服务名(灰度发布等场景可用)
        template.header("X-Source-App", "openfeign-lab");

        log.debug("[FeignInterceptor] 注入 Header: {}={}, 请求路径: {}",
                TRACE_ID_HEADER, traceId, template.path());
    }
}
