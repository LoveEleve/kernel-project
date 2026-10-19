package com.scalab.tracing.a;

import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A01 Span 生命周期验证（对照 D1 §二/§三）
 * 
 * 验证点:
 * 1. startScopedSpan 创建 span + traceId 输出
 * 2. nextSpan + withSpan 手动 scope 管理
 * 3. currentSpan 获取当前 span
 * 4. Span.end() + scope 清理
 */
@RestController
@RequestMapping("/a01")
public class A01SpanLifecycleController {
    private static final Logger log = LoggerFactory.getLogger(A01SpanLifecycleController.class);
    private final Tracer tracer;

    public A01SpanLifecycleController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** startScopedSpan——一步创建+scope */
    @GetMapping("/scoped")
    public String scopedSpan() {
        ScopedSpan scoped = tracer.startScopedSpan("a01.scoped");
        try {
            Span current = tracer.currentSpan();
            String traceId = current != null ? current.context().traceId() : "null";
            log.info("[A01] ScopedSpan created, traceId={}", traceId);
            return "scopedSpan ok | traceId=" + traceId + " | spanName=a01.scoped";
        } finally {
            scoped.end();
        }
    }

    /** nextSpan + withSpan——精细控制 */
    @GetMapping("/manual")
    public String manualSpan() {
        Span span = tracer.nextSpan().name("a01.manual").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            Span current = tracer.currentSpan();
            String traceId = current != null ? current.context().traceId() : "null";
            log.info("[A01] Manual span created, traceId={}", traceId);
            span.tag("method", "GET");
            span.event("manual-span-created");
            return "manualSpan ok | traceId=" + traceId + " | tag=method:GET";
        } finally {
            span.end();
        }
    }

    /** 嵌套 span——parentId 验证 */
    @GetMapping("/nested")
    public String nestedSpan() {
        Span outer = tracer.nextSpan().name("a01.outer").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(outer)) {
            String outerTraceId = outer.context().traceId();
            String outerSpanId = outer.context().spanId();

            Span inner = tracer.nextSpan().name("a01.inner").start();
            try {
                String innerTraceId = inner.context().traceId();
                String innerParentId = inner.context().parentId();
                return String.format("nested ok | traceId=%s | outer.spanId=%s | inner.parentId=%s | sameTrace=%s",
                        innerTraceId, outerSpanId, innerParentId,
                        outerTraceId.equals(innerTraceId));
            } finally {
                inner.end();
            }
        } finally {
            outer.end();
        }
    }

    /** currentSpan——无手动创建时的当前 span（由框架自动注入） */
    @GetMapping("/current")
    public String currentSpan() {
        Span current = tracer.currentSpan();
        String traceId = current != null ? current.context().traceId() : "null";
        String spanName = current != null ? "framework-auto" : "null";
        log.info("[A01] currentSpan, traceId={}", traceId);
        return "currentSpan | traceId=" + traceId + " | name=" + spanName;
    }
}
