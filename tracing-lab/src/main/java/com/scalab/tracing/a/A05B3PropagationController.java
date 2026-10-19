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
 * A05 B3 传播格式验证（对照 D2 §三）
 * 
 * 验证点:
 * 1. B3 single header 格式: b3: {traceId}-{spanId}-{sampledFlag}
 * 2. B3_MULTI 多 header: X-B3-TraceId/X-B3-SpanId/X-B3-Sampled
 * 3. B3 header 回显
 */
@RestController
@RequestMapping("/a05")
public class A05B3PropagationController {
    private static final Logger log = LoggerFactory.getLogger(A05B3PropagationController.class);
    private final Tracer tracer;

    public A05B3PropagationController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** 回显请求中的 B3 headers */
    @GetMapping("/echo-b3")
    public String echoB3(HttpServletRequest request) {
        String b3 = request.getHeader("b3");
        String xB3TraceId = request.getHeader("X-B3-TraceId");
        String xB3SpanId = request.getHeader("X-B3-SpanId");
        String xB3Sampled = request.getHeader("X-B3-Sampled");
        String xB3ParentSpanId = request.getHeader("X-B3-ParentSpanId");

        boolean hasSingle = b3 != null;
        boolean hasMulti = xB3TraceId != null || xB3SpanId != null;

        log.info("[A05] B3 headers: single={}, multi={} (traceId={}, spanId={}, sampled={}, parentId={})",
                hasSingle, hasMulti, xB3TraceId, xB3SpanId, xB3Sampled, xB3ParentSpanId);
        return String.format("b3 | single=%s | multi=%s | b3=%s | X-B3-TraceId=%s",
                hasSingle, hasMulti,
                b3 != null ? b3 : "null",
                xB3TraceId != null ? xB3TraceId : "null");
    }

    /** 生成 B3 single header 格式（1 个 header） */
    @GetMapping("/single-format")
    public String singleFormat() {
        Span span = tracer.nextSpan().name("a05.b3-single").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            String traceId = span.context().traceId();
            String spanId = span.context().spanId();
            // B3 single: {traceId}-{spanId}-{sampledFlag}
            // 采样标志 1 = sampled（sampling.probability=1.0 默认全采样）
            String b3single = String.format("%s-%s-1", traceId, spanId);
            log.info("[A05] B3 single format: {}", b3single);
            return "b3-single | format=" + b3single + " | traceIdLen=" + traceId.length();
        } finally {
            span.end();
        }
    }

    /** 生成 B3_MULTI headers（3-4 个独立 header） */
    @GetMapping("/multi-format")
    public String multiFormat(HttpServletResponse response) {
        Span span = tracer.nextSpan().name("a05.b3-multi").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            String traceId = span.context().traceId();
            String spanId = span.context().spanId();
            response.setHeader("X-B3-TraceId", traceId);
            response.setHeader("X-B3-SpanId", spanId);
            response.setHeader("X-B3-Sampled", "1");
            int headerCount = 3;
            if (span.context().parentId() != null) {
                response.setHeader("X-B3-ParentSpanId", span.context().parentId());
                headerCount++;
            }
            log.info("[A05] B3_MULTI injected: TraceId={}, SpanId={}, headers={}", traceId, spanId, headerCount);
            return String.format("b3-multi | traceId=%s | spanId=%s | headers=%d", traceId, spanId, headerCount);
        } finally {
            span.end();
        }
    }
}
