package com.scalab.tracing.a;

import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A06 Baggage 注入 + MDC 输出验证（对照 D6 §二/§三/§五）
 * 
 * 验证点:
 * 1. createBaggageInScope —— 一步创建+scope
 * 2. BaggageInScope.close() —— try-with-resources 自动清理
 * 3. MDC 中 baggage 值可见
 * 4. 多个 baggage 字段同时使用
 */
@RestController
@RequestMapping("/a06")
public class A06BaggageController {
    private static final Logger log = LoggerFactory.getLogger(A06BaggageController.class);
    private final Tracer tracer;

    public A06BaggageController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** 单个 baggage——创建+MDC 验证 */
    @GetMapping("/single")
    public String singleBaggage() {
        Span span = tracer.nextSpan().name("a06.single").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            BaggageInScope tenant = tracer.createBaggageInScope("tenant-id", "acme-corp");
            try {
                String mdcTenant = MDC.get("tenant-id");
                log.info("[A06] Baggage tenant-id set, MDC={}", mdcTenant);
                return "baggage ok | tenant-id=" + mdcTenant;
            } finally {
                tenant.close();
            }
        } finally {
            span.end();
        }
    }

    /** 多个 baggage 同时使用 */
    @GetMapping("/multi")
    public String multiBaggage() {
        Span span = tracer.nextSpan().name("a06.multi").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            BaggageInScope tenant = tracer.createBaggageInScope("tenant-id", "acme-corp");
            try {
                BaggageInScope region = tracer.createBaggageInScope("region", "us-east");
                try {
                    String mdcTenant = MDC.get("tenant-id");
                    String mdcRegion = MDC.get("region");
                    log.info("[A06] Multi baggage: tenant={}, region={}", mdcTenant, mdcRegion);
                    return String.format("multi ok | tenant-id=%s | region=%s",
                            mdcTenant, mdcRegion);
                } finally {
                    region.close();
                }
            } finally {
                tenant.close();
            }
        } finally {
            span.end();
        }
    }

    /** 验证 baggage.close() 后 MDC 被清理 */
    @GetMapping("/cleanup")
    public String cleanupBaggage() {
        Span span = tracer.nextSpan().name("a06.cleanup").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            String before, after;
            BaggageInScope session = tracer.createBaggageInScope("session-id", "abc123");
            try {
                before = MDC.get("session-id");
            } finally {
                session.close();
            }
            after = MDC.get("session-id");
            log.info("[A06] Cleanup: before={}, after={}", before, after);
            return String.format("cleanup ok | before=%s | after=%s", before, after == null ? "null" : "NOT_CLEANED");
        } finally {
            span.end();
        }
    }
}
