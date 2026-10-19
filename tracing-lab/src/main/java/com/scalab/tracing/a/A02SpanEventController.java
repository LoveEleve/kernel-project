package com.scalab.tracing.a;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A02 Span 事件/标签/错误验证（对照 D1 §二/§三）
 * 
 * 验证点:
 * 1. event(String) — 简单事件
 * 2. tag(String, String/long/double/boolean) — 多种 tag 重载
 * 3. error(Throwable) — 异常记录
 */
@RestController
@RequestMapping("/a02")
public class A02SpanEventController {
    private static final Logger log = LoggerFactory.getLogger(A02SpanEventController.class);
    private final Tracer tracer;

    public A02SpanEventController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** event + tag 组合 */
    @GetMapping("/enriched")
    public String enrichedSpan() {
        Span span = tracer.nextSpan().name("a02.enriched").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            span.tag("method", "GET");
            span.tag("qps", 1000L);           // tag(String, long) 重载
            span.tag("latency", 3.5);         // tag(String, double) 重载
            span.tag("cached", true);         // tag(String, boolean) 重载
            span.event("span-started");
            span.event("processing-complete");
            String traceId = span.context().traceId();
            log.info("[A02] Enriched span, traceId={}, tags=method/qps/latency/cached", traceId);
            return "enriched ok | traceId=" + traceId;
        } finally {
            span.end();
        }
    }

    /** error 记录 */
    @GetMapping("/error")
    public String errorSpan() {
        Span span = tracer.nextSpan().name("a02.error").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            span.tag("status", "error");
            span.error(new RuntimeException("simulated failure for testing"));
            String traceId = span.context().traceId();
            log.info("[A02] Error span, traceId={}", traceId);
            return "error ok (span recorded with error) | traceId=" + traceId;
        } finally {
            span.end();
        }
    }

    /** 带时间戳的 event */
    @GetMapping("/timed-event")
    public String timedEventSpan() {
        Span span = tracer.nextSpan().name("a02.timed-event").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            long startMicros = System.nanoTime() / 1000;
            span.event("started", startMicros, java.util.concurrent.TimeUnit.MICROSECONDS);
            span.tag("type", "timed");
            String traceId = span.context().traceId();
            log.info("[A02] Timed-event span, traceId={}", traceId);
            return "timed-event ok | traceId=" + traceId;
        } finally {
            span.end();
        }
    }
}
