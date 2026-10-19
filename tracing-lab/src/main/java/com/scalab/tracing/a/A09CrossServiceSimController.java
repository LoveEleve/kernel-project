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
 * A09 跨服务 trace 透传模拟（对照 D2 §一/§五）
 * 
 * 模拟 Service A → inject → HTTP → extract → Service B 的 trace 传播链。
 * tracing-lab 自包含: /inject 模拟 Service A inject, /receive 模拟 Service B extract。
 */
@RestController
@RequestMapping("/a09")
public class A09CrossServiceSimController {
    private static final Logger log = LoggerFactory.getLogger(A09CrossServiceSimController.class);
    private final Tracer tracer;

    public A09CrossServiceSimController(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Service A: 创建 span 并 inject traceparent header 到 response。
     * 实际跨服务时这个 header 会被 HTTP client（RestTemplate/WebClient）自动携带。
     */
    @GetMapping("/inject")
    public String injectA(HttpServletResponse response) {
        Span spanA = tracer.nextSpan().name("a09.service-a").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(spanA)) {
            String traceId = spanA.context().traceId();
            String spanId = spanA.context().spanId();
            String traceparent = String.format("00-%s-%s-01", traceId, spanId);
            response.setHeader("traceparent", traceparent);
            log.info("[A09] Service A inject: traceparent={}", traceparent);
            return "inject | traceparent=" + traceparent;
        } finally {
            spanA.end();
        }
    }

    /**
     * Service B: 从 request header 中 extract traceparent，创建子 span。
     * 验证子 span 继承上游 traceId 且 parentId 正确。
     */
    @GetMapping("/receive")
    public String receiveB(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        if (traceparent == null || traceparent.isEmpty()) {
            return "receive | error=no traceparent header (call /a09/inject first or send header)";
        }
        String[] parts = traceparent.split("-");
        if (parts.length != 4) {
            return "receive | error=invalid traceparent format: " + traceparent;
        }
        String upstreamTraceId = parts[1];
        String upstreamSpanId = parts[2];

        Span spanB = tracer.nextSpan().name("a09.service-b").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(spanB)) {
            String spanBTraceId = spanB.context().traceId();
            String spanBParentId = spanB.context().parentId();
            boolean sameTrace = upstreamTraceId.equals(spanBTraceId);
            // 注意: 当前 context 父 span 由 scope 决定，parentId 反映实际父关系
            // 手动 extract 场景下需 Propagator.extract() 设置 context——本简化模拟直接对比
            log.info("[A09] Service B receive: sameTrace={}, upstreamSpanId={}, B.parentId={}",
                    sameTrace, upstreamSpanId, spanBParentId);
            return String.format("receive | sameTrace=%s | upstream=%s | B.parentId=%s | B.spanId=%s",
                    sameTrace, upstreamSpanId.substring(0, 8),
                    spanBParentId != null ? spanBParentId.substring(0, 8) : "null",
                    spanB.context().spanId().substring(0, 8));
        } finally {
            spanB.end();
        }
    }

    /**
     * 完整模拟 A→B: 同一请求内创建 Service A span → inject → "传递" → extract → Service B span
     */
    @GetMapping("/ab")
    public String crossAB(HttpServletResponse response) {
        Span spanA = tracer.nextSpan().name("a09.service-a").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(spanA)) {
            String traceIdA = spanA.context().traceId();
            String spanIdA = spanA.context().spanId();
            // 手动构造 traceparent（相当于 HTTP 传输）
            response.setHeader("x-sim-traceparent",
                    String.format("00-%s-%s-01", traceIdA, spanIdA));

            // "Service B" 处理——子 span 自动继承当前 scope 的 traceId
            Span spanB = tracer.nextSpan().name("a09.service-b").start();
            try {
                String traceIdB = spanB.context().traceId();
                String spanBParent = spanB.context().parentId();
                boolean sameTrace = traceIdA.equals(traceIdB);
                boolean correctParent = spanIdA.equals(spanBParent);
                log.info("[A09] A→B: sameTrace={}, correctParent={}", sameTrace, correctParent);
                return String.format("A-B ok | sameTrace=%s | B.parentId==A.spanId=%s", sameTrace, correctParent);
            } finally {
                spanB.end();
            }
        } finally {
            spanA.end();
        }
    }
}
