package com.scalab.tracing.b;

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
 * B04 自定义 Baggage 字段——tenant-id 跨服务传播（对照 D6 §二/§三/§八）
 * 
 * tenant-id/region/session-id 已通过 application.yml 注册为 remote-fields
 * （Boot 3.2.5 仅支持 remote-fields；local-fields/tag-fields 属性在更晚版本/手动构造 W3CPropagation 时才可用）。
 * remote-fields：出现在 W3C baggage header（跨服务传播）+ MDC（本地日志）。
 */
@RestController
@RequestMapping("/b04")
public class B04TenantBaggageController {
    private static final Logger log = LoggerFactory.getLogger(B04TenantBaggageController.class);
    private final Tracer tracer;

    public B04TenantBaggageController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** 设置 tenant-id baggage + MDC 验证 */
    @GetMapping("/set-tenant")
    public String setTenant() {
        Span span = tracer.nextSpan().name("b04.tenant").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            BaggageInScope tenant = tracer.createBaggageInScope("tenant-id", "acme-corp");
            try {
                span.tag("tenant-id", "acme-corp");
                String mdcTenant = MDC.get("tenant-id");
                log.info("[B04] Baggage tenant-id={} (MDC={}, tagged in span)", "acme-corp", mdcTenant);
                return String.format("b04 | tenant-id=acme-corp | MDC=%s | tagged=true", mdcTenant);
            } finally {
                tenant.close();
            }
        } finally {
            span.end();
        }
    }

    /** 演示 remote-fields（传播 + MDC）与 local 概念的区别 */
    @GetMapping("/explain")
    public String explainBaggage() {
        Span span = tracer.nextSpan().name("b04.explain").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            return "b04 | remote-fields: tenant-id,region,session-id (propagated via W3C baggage header + MDC)\n"
                    + "     | local-only fields: not supported by Boot 3.2.5 property (manual W3CPropagation construction only)";
        } finally {
            span.end();
        }
    }
}
