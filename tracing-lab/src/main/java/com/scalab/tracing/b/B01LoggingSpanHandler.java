package com.scalab.tracing.b;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * B01 自定义 SpanHandler——记录每个 Span 的生命周期事件（对照 D7 §二）
 * 
 * BraveAutoConfiguration 自动收集所有 SpanHandler Bean。
 * end() 在 span 结束时调用，abandon/error 也会触发。
 */
@Component
public class B01LoggingSpanHandler extends SpanHandler {
    private static final Logger log = LoggerFactory.getLogger(B01LoggingSpanHandler.class);

    @Override
    public boolean end(TraceContext context, MutableSpan span, Cause cause) {
        String spanName = span.name();
        String traceId = context.traceIdString();
        String spanId = context.spanIdString();
        long duration = span.finishTimestamp() - span.startTimestamp();

        if (cause == Cause.FINISHED) {
            log.info("[B01] Span ended: name={}, traceId={}, spanId={}, duration={}μs, tags={}",
                    spanName, traceId, spanId, duration, span.tags());
        } else if (cause == Cause.ABANDONED) {
            log.info("[B01] Span abandoned: name={}, traceId={}, spanId={}", spanName, traceId, spanId);
        }

        return true; // 允许后续 handler 处理
    }
}
