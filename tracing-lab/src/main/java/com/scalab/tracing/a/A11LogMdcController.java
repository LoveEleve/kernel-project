package com.scalab.tracing.a;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A11 日志 MDC 验证（对照 D3 §五 / D8 §五）
 * 
 * 验证点:
 * 1. traceId/spanId 出现在日志格式中（logging.pattern.level 配置）
 * 2. MDC 中可直接读取 traceId/spanId
 * 3. 无 span 时 MDC 显示 -
 */
@RestController
@RequestMapping("/a11")
public class A11LogMdcController {
    private static final Logger log = LoggerFactory.getLogger(A11LogMdcController.class);
    private final Tracer tracer;

    public A11LogMdcController(Tracer tracer) {
        this.tracer = tracer;
    }

    /** 验证 MDC 中的 traceId/spanId */
    @GetMapping("/check")
    public String checkMdc() {
        String mdcTraceId = MDC.get("traceId");
        String mdcSpanId = MDC.get("spanId");
        Span current = tracer.currentSpan();
        String apiTraceId = current != null ? current.context().traceId() : "null";

        log.info("[A11] MDC check: traceId={}, spanId={}, apiTraceId={}", mdcTraceId, mdcSpanId, apiTraceId);

        boolean mdcMatch = apiTraceId.equals(mdcTraceId);
        return String.format("mdc | traceId=%s | spanId=%s | apiTraceId=%s | match=%s",
                mdcTraceId, mdcSpanId, apiTraceId, mdcMatch);
    }

    /** 创建自定义 span 后验证 MDC 更新 */
    @GetMapping("/custom-span")
    public String customSpanMdc() {
        Span span = tracer.nextSpan().name("a11.custom").start();
        try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
            String mdcTraceId = MDC.get("traceId");
            String mdcSpanId = MDC.get("spanId");
            String apiSpanId = span.context().spanId();

            log.info("[A11] Custom span MDC: traceId={}, spanId={}, apiSpanId={}",
                    mdcTraceId, mdcSpanId, apiSpanId);

            boolean mdcMatch = apiSpanId.equals(mdcSpanId);
            return String.format("custom-md | traceId=%s | mdcSpanId=%s | apiSpanId=%s | match=%s",
                    mdcTraceId, mdcSpanId, apiSpanId, mdcMatch);
        } finally {
            span.end();
        }
    }

    /** 验证日志格式——grep 日志确认 traceId/spanId 出现在每行 */
    @GetMapping("/format")
    public String logFormat() {
        log.info("[A11] Log format test — traceId/spanId should appear in log pattern");
        log.debug("[A11] DEBUG level: same traceId/spanId format");
        log.warn("[A11] WARN level: same traceId/spanId format");
        return "log-format | grep tracing-lab.log for [A11] to verify traceId/spanId in every line";
    }
}
