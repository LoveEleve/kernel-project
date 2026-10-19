package com.scalab.tracing.a;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A03 SpanInScope + try-with-resources 验证（对照 D1 §二.2 / §四）
 * 
 * 验证点:
 * 1. try-with-resources 自动关闭 scope
 * 2. 嵌套 scope 正确恢复
 * 3. scope 关闭后恢复框架自动 Span
 */
@RestController
@RequestMapping("/a03")
public class A03SpanScopeController {
    private static final Logger log = LoggerFactory.getLogger(A03SpanScopeController.class);
    private final Tracer tracer;

    public A03SpanScopeController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** try-with-resources——scope 自动 close */
    @GetMapping("/auto-close")
    public String autoCloseScope() {
        Span span = tracer.nextSpan().name("a03.auto-close").start();
        Span currentBeforeClose;
        Span currentAfterClose;
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            span.tag("phase", "active");
            currentBeforeClose = tracer.currentSpan();
        } // ws.close() → 恢复框架自动 Span（非 null！）
        currentAfterClose = tracer.currentSpan();
        String result = String.format("scope ok | in-scope=%s | after-close-restored=%s",
                currentBeforeClose != null ? currentBeforeClose.context().spanId().substring(0, 8) : "null",
                currentAfterClose != null ? currentAfterClose.context().spanId().substring(0, 8) : "null");
        span.end();
        return result;
    }

    /** 嵌套 scope——pop 恢复上层 */
    @GetMapping("/nested-scope")
    public String nestedScope() {
        Span outer = tracer.nextSpan().name("a03.outer").start();
        try (Tracer.SpanInScope wsOuter = tracer.withSpan(outer)) {
            outer.tag("layer", "outer");

            Span inner = tracer.nextSpan().name("a03.inner").start();
            try (Tracer.SpanInScope wsInner = tracer.withSpan(inner)) {
                inner.tag("layer", "inner");
                Span current = tracer.currentSpan();
                String innerId = current != null ? current.context().spanId().substring(0, 8) : "null";
                log.info("[A03] Nested inner active, spanId={}", innerId);
            } // wsInner.close() → 恢复 outer

            Span current = tracer.currentSpan();
            String outerId = current != null ? current.context().spanId().substring(0, 8) : "null";
            inner.end();
            return "nested ok | innerId=" + inner.context().spanId().substring(0, 8)
                    + " | restoredToOuter=" + outerId;
        } finally {
            outer.end();
        }
    }

    /** 连续 scope——前一个 close 后后一个可正常创建 */
    @GetMapping("/sequential")
    public String sequentialScopes() {
        Span span1 = tracer.nextSpan().name("a03.seq-1").start();
        String id1;
        try (Tracer.SpanInScope ws = tracer.withSpan(span1)) {
            id1 = span1.context().spanId().substring(0, 8);
        }
        span1.end();

        Span span2 = tracer.nextSpan().name("a03.seq-2").start();
        String id2;
        try (Tracer.SpanInScope ws = tracer.withSpan(span2)) {
            id2 = span2.context().spanId().substring(0, 8);
        }
        span2.end();

        return "sequential ok | span1=" + id1 + " | span2=" + id2 + " | different=" + !id1.equals(id2);
    }
}
