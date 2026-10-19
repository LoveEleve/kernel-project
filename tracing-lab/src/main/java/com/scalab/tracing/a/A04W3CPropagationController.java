package com.scalab.tracing.a;

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
 * A04 W3C propagation 格式验证（对照 D2 §二 / §五）
 * 
 * 验证点:
 * 1. traceparent header 格式 (version-traceId-parentId-flags)
 * 2. tracestate header 格式
 * 3. HTTP 请求头中的 traceparent 回显
 */
@RestController
@RequestMapping("/a04")
public class A04W3CPropagationController {
    private static final Logger log = LoggerFactory.getLogger(A04W3CPropagationController.class);
    private final Tracer tracer;

    public A04W3CPropagationController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** 回显请求中的 W3C headers */
    @GetMapping("/echo-headers")
    public String echoHeaders(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        String tracestate = request.getHeader("tracestate");
        String b3 = request.getHeader("b3");
        String b3TraceId = request.getHeader("X-B3-TraceId");

        String tpInfo = traceparent != null ? traceparent : "NOT_PRESENT";
        String tsInfo = tracestate != null ? tracestate : "NOT_PRESENT";
        String b3Info = (b3 != null || b3TraceId != null) ? "PRESENT" : "NOT_PRESENT";

        log.info("[A04] echo-headers: traceparent={}, tracestate={}, b3={}", tpInfo, tsInfo, b3Info);
        return String.format("headers | traceparent=%s | tracestate=%s | b3=%s", tpInfo, tsInfo, b3Info);
    }

    /** 创建 span 后查看其 traceparent 格式 */
    @GetMapping("/inspect-format")
    public String inspectFormat() {
        Span span = tracer.nextSpan().name("a04.format").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            String traceId = span.context().traceId();
            String spanId = span.context().spanId();
            // traceparent 格式: 00-{traceId}-{spanId}-01
            String traceparent = String.format("00-%s-%s-01", traceId, spanId);
            log.info("[A04] traceparent format: {} (traceId={}chars, spanId={}chars)",
                    traceparent, traceId.length(), spanId.length());
            return String.format("format | traceparent=%s | traceIdLen=%d | spanIdLen=%d",
                    traceparent, traceId.length(), spanId.length());
        } finally {
            span.end();
        }
    }

    /** 手动设置 response 头模拟 inject */
    @GetMapping("/inject-demo")
    public String injectDemo(HttpServletResponse response) {
        Span span = tracer.nextSpan().name("a04.inject").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            String traceparent = String.format("00-%s-%s-01",
                    span.context().traceId(), span.context().spanId());
            response.setHeader("traceparent", traceparent);
            response.setHeader("tracestate", "a04=demo");
            log.info("[A04] Injected headers: traceparent={}, tracestate=a04=demo", traceparent);
            return "injected | traceparent=" + traceparent + " | tracestate=a04=demo";
        } finally {
            span.end();
        }
    }
}
