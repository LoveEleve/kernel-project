package com.scalab.tracing.b;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B03 自定义传播 header（对照 D2 §四 Getter/Setter 接口）
 * 
 * 在标准 W3C traceparent/baggage 之外追加自定义业务 header，
 * 演示 Propagator 的载体扩展性（carrier = HTTP headers / Kafka headers / gRPC metadata）。
 */
@RestController
@RequestMapping("/b03")
public class B03CustomHeaderController {
    private static final Logger log = LoggerFactory.getLogger(B03CustomHeaderController.class);
    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Tracer tracer;

    public B03CustomHeaderController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** inject: 设置标准 traceparent + 自定义 X-Correlation-Id */
    @GetMapping("/inject")
    public String injectCustom(HttpServletResponse response) {
        Span span = tracer.nextSpan().name("b03.custom").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            String traceId = span.context().traceId();
            String correlationId = traceId.substring(0, 8) + "-custom";

            // 标准 trace
            response.setHeader("traceparent",
                    String.format("00-%s-%s-01", traceId, span.context().spanId()));
            // 自定义业务 header
            response.setHeader(X_CORRELATION_ID, correlationId);

            log.info("[B03] Injected: traceparent, X-Correlation-Id={}", correlationId);
            return String.format("inject | traceId=%s | %s=%s",
                    traceId, X_CORRELATION_ID, correlationId);
        } finally {
            span.end();
        }
    }

    /** extract: 读取自定义 header 并关联到 span */
    @GetMapping("/extract")
    public String extractCustom(HttpServletRequest request) {
        String correlationId = request.getHeader(X_CORRELATION_ID);

        Span span = tracer.nextSpan().name("b03.extract").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            if (correlationId != null && !correlationId.isEmpty()) {
                span.tag("correlation-id", correlationId);
                log.info("[B03] Extracted X-Correlation-Id={}, tagged to span", correlationId);
                return String.format("extract | traceId=%s | %s=%s (tagged)", 
                        span.context().traceId(), X_CORRELATION_ID, correlationId);
            }
            log.info("[B03] No X-Correlation-Id header");
            return "extract | no " + X_CORRELATION_ID + " header (tag not set)";
        } finally {
            span.end();
        }
    }
}
